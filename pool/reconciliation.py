"""Incremental canonical-chain audits, with durable catch-up barriers after a reorg."""
import json
import time
import urllib.error


class ChainUncertain(RuntimeError):
    pass


def checked_header(node, height):
    header = node.header(height)
    if not isinstance(header, dict) or not isinstance(header.get("id"), str) or not header["id"]:
        raise ChainUncertain("Canonical header is unavailable; payouts are paused")
    return header


def audit_block(node, ledger, block, height, settle_allowed=True):
    if block["height"] > height:
        if block["status"] == "confirmed":
            raise ChainUncertain("A credited block is above the current applied tip; payouts are paused")
        return
    header = checked_header(node, block["height"])
    solutions = header.get("powSolutions")
    if not isinstance(solutions, dict) or not isinstance(solutions.get("pk"), str) or not isinstance(solutions.get("n"), str):
        raise ChainUncertain("Canonical proof is unavailable; payouts are paused")
    try:
        message = node.message(header)
    except (ValueError, TypeError, KeyError) as error:
        raise ChainUncertain("Canonical work message is unavailable; payouts are paused") from error
    matches = (solutions["pk"] == block["pk"] and solutions["n"] == block["nonce"] and message == block["msg"])
    if block["status"] == "confirmed":
        if not matches or header["id"] != block["hash"]:
            ledger.chain_halt(block, header["id"], "A credited block was reorganized. Review balances before resuming payouts.")
            return
        if height - block["height"] + 1 < node.config["confirmations"]:
            raise ChainUncertain("A credited block lost maturity; payouts are paused")
        return
    if not matches:
        if block["status"] != "orphaned":
            ledger.orphan(block["id"])
        return
    if height - block["height"] + 1 < node.config["confirmations"]:
        return
    if not settle_allowed:
        return
    full = node.rpc("/blocks/" + header["id"])
    try:
        txs = full["blockTransactions"]["transactions"]
        reward = sum(output["value"] for tx in txs for output in tx["outputs"] if output["ergoTree"] == node.reward_script)
    except (TypeError, KeyError) as error:
        raise ChainUncertain("Canonical block transactions are unavailable") from error
    # A second canonical check makes a branch change during a full-block RPC retryable.
    if checked_header(node, block["height"])["id"] != header["id"]:
        raise ChainUncertain("Canonical chain changed during settlement")
    ledger.settle(block, header["id"], reward)


def audit_payout(node, ledger, payout):
    missing = False
    try:
        tx = node.rpc("/wallet/transactionById?id=" + payout["id"])
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        tx = None
        missing = True
    if tx is None and not missing:
        raise ChainUncertain("Wallet confirmation response is empty; payouts are paused")
    if tx is not None and (not isinstance(tx, dict) or type(tx.get("numConfirmations")) is not int or tx["numConfirmations"] < 0):
        raise ChainUncertain("Wallet confirmation response is ambiguous; payouts are paused")
    confirmations = tx["numConfirmations"] if tx is not None else 0
    if confirmations >= node.config["confirmations"]:
        if payout["status"] != "confirmed":
            ledger.payout_status(payout["id"], "confirmed")
    elif confirmations > 0 or payout["status"] == "confirmed":
        # The original signed transaction and balance reservation are retained.
        if payout["status"] != "broadcast":
            ledger.payout_status(payout["id"], "broadcast")
    return confirmations


def _walk_blocks(node, ledger, state, key, height, limit, boundary=None, minimum=None, settle_allowed=True):
    rows = ledger.maintenance_blocks(state[key], limit, minimum, boundary)
    for block in rows:
        audit_block(node, ledger, block, height, settle_allowed)
        if ledger.halted():
            return False
        state[key] = block["id"]
    return len(rows) < limit


def _walk_payouts(node, ledger, state, key, limit, boundary=None):
    rows = ledger.maintenance_payouts(state[key], limit, boundary)
    for payout in rows:
        audit_payout(node, ledger, payout)
        state[key] = payout["checkpoint"]
    return len(rows) < limit


def reconcile_chain(node, ledger, height):
    """Verify a pinned ancestor every cycle; deep changes require a complete bounded rescan.

    An initial/reorg scan pauses new payments until every existing block and payment
    has been checked. Stable-chain work is capped at 64 blocks and 8 historical
    payments per cycle, regardless of the size of the database. A periodic cursor
    keeps old orphan candidates and confirmed payments under review as well.
    """
    if type(height) is not int or height < 1:
        raise ChainUncertain("Applied chain height is invalid")
    state = ledger.chain_state()
    if state is None:
        state = {"tipHeight": None, "tipId": None, "frontier": 0, "recent": [0, 0],
                 "blockAudit": 0, "payoutAudit": 0, "rescanBlock": 0, "rescanPayout": 0,
                 "blockBoundary": ledger.maintenance_boundary(), "payoutBoundary": ledger.maintenance_payout_boundary(),
                 "scanning": True}
        ledger.save_chain_state(state)
    starting_tip = checked_header(node, height)["id"]
    changed = False
    if state["tipHeight"] is not None:
        changed = height < state["tipHeight"] or checked_header(node, state["tipHeight"])["id"] != state["tipId"]
    if changed:
        state.update(scanning=True, rescanBlock=0, rescanPayout=0,
                     blockBoundary=ledger.maintenance_boundary(), payoutBoundary=ledger.maintenance_payout_boundary())
        ledger.save_chain_state(state)
    # Inspect credits before any wallet or migration RPC, even after a height rollback.
    if state["scanning"]:
        blocks_done = _walk_blocks(node, ledger, state, "rescanBlock", height, 16, state["blockBoundary"], settle_allowed=False)
        if ledger.halted():
            return False
        payouts_done = _walk_payouts(node, ledger, state, "rescanPayout", 4, state["payoutBoundary"])
        if blocks_done and payouts_done:
            state["scanning"] = False
            state["frontier"] = state["blockBoundary"]
    if not state["scanning"]:
        _walk_blocks(node, ledger, state, "frontier", height, 16)
        if ledger.halted():
            return False
        # Recheck immature/orphan candidates in a bounded moving window.
        minimum = max(1, height - max(256, node.config["confirmations"] * 4))
        recent = ledger.maintenance_recent_blocks(*state["recent"], minimum)
        for block in recent:
            audit_block(node, ledger, block, height)
            if ledger.halted():
                return False
            state["recent"] = [block["height"], block["id"]]
        if len(recent) < 16:
            state["recent"] = [0, 0]
        if ledger.halted():
            return False
        if _walk_blocks(node, ledger, state, "blockAudit", height, 16):
            state["blockAudit"] = 0
        if ledger.halted():
            return False
        if _walk_payouts(node, ledger, state, "payoutAudit", 4):
            state["payoutAudit"] = 0
    tip = checked_header(node, height)
    if tip["id"] != starting_tip:
        raise ChainUncertain("Canonical tip changed during maintenance")
    # The previously verified ancestor must remain canonical after all RPCs.
    if state["tipHeight"] is not None and not changed:
        if checked_header(node, state["tipHeight"])["id"] != state["tipId"]:
            raise ChainUncertain("Canonical chain changed during maintenance")
    state.update(tipHeight=height, tipId=tip["id"], checkedAt=time.time())
    ledger.save_chain_state(state)
    # Always spend at least one stable cycle after a rollback/branch change.
    return not state["scanning"] and not changed


def verify_payout_tip(node, ledger):
    state = ledger.chain_state()
    if not state or state["scanning"] or ledger.halted():
        raise ChainUncertain("Chain audit is incomplete; new payouts are paused")
    height, _ = node.applied_tip()
    if height < state["tipHeight"]:
        raise ChainUncertain("Applied height decreased before payment preparation")
    if checked_header(node, state["tipHeight"])["id"] != state["tipId"]:
        raise ChainUncertain("Canonical chain changed before payment preparation")


def retry_payments(node, ledger):
    """Bounded retries always retain the same signed transaction and reserved debit."""
    for payout in ledger.maintenance_payouts(limit=8, pending=True):
        confirmations = audit_payout(node, ledger, payout)
        if confirmations > 0:
            continue
        try:
            node.rpc("/transactions", json.loads(payout["raw"]))
            ledger.payout_status(payout["id"], "broadcast")
        except urllib.error.HTTPError as error:
            if error.code != 400:
                raise
    return not ledger.has_pending_payouts()
