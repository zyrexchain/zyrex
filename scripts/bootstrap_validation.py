"""Strict public checkpoint validation and honest bootstrap evidence metadata."""
import hashlib
import re
import subprocess
from pathlib import Path

GENESIS = "0f6e2d9181f10231dafa9e1aa3eb297218204e2e2c38f3c65ae0ab367629d336"
DAA_EPOCH = 64
VOTING_EPOCH = 128
EPOCH_PROOF_HEIGHT = 385


def hex_pin(value, length, name):
    if not isinstance(value, str) or not re.fullmatch("[0-9a-f]{" + str(length) + "}", value):
        raise ValueError("Invalid " + name + " pin")
    return value


def height_pin(value, name="height"):
    if type(value) is not int or value <= 1 or value > (1 << 31) - 1:
        raise ValueError("Invalid applied " + name)
    return value


def validate_expected(result):
    if not isinstance(result, dict) or result.get("network") != "testnet" or result.get("genesisBlockId") != GENESIS:
        raise ValueError("Checkpoint must pin the Zyrex public testnet genesis")
    height = height_pin(result.get("fullHeight"))
    headers = result.get("headersHeight")
    if type(headers) is not int or headers < height or headers > (1 << 31) - 1:
        raise ValueError("Checkpoint headers must include its applied full height")
    hex_pin(result.get("bestFullHeaderId"), 64, "block ID")
    hex_pin(result.get("stateRoot"), 66, "state root")
    for name, length in (("verificationSourceCommit", 40), ("verificationJarSha256", 64)):
        if name in result:
            hex_pin(result[name], length, name)
    if "verificationSourceTreeDirty" in result and type(result["verificationSourceTreeDirty"]) is not bool:
        raise ValueError("Checkpoint source tree cleanliness must be boolean")
    if "chainRules" in result and result["chainRules"] != {"daaEpochLength": DAA_EPOCH, "votingEpochLength": VOTING_EPOCH}:
        raise ValueError("Checkpoint chain rule metadata does not match public testnet")
    return result


def checkpoint(status, block):
    if not isinstance(status, dict) or status.get("network") != "testnet" \
            or status.get("genesisId") != GENESIS or status.get("ready") is not True:
        raise ValueError("Public checkpoint source is not ready or has a foreign network identity")
    indexed = height_pin(status.get("indexedHeight"), "indexed height")
    node = status.get("node", {})
    full = height_pin(node.get("fullHeight"), "node height")
    if type(node.get("headersHeight")) is not int or node["headersHeight"] < full:
        raise ValueError("Public checkpoint node has unapplied or inconsistent headers")
    height = min(indexed, full)
    if not isinstance(block, dict) or not isinstance(block.get("header"), dict):
        raise ValueError("Checkpoint must contain a full native block")
    header = block["header"]
    ident = hex_pin(header.get("id"), 64, "block ID")
    root = hex_pin(header.get("stateRoot"), 66, "state root")
    transactions = block.get("blockTransactions", {})
    if type(header.get("height")) is not int or header["height"] != height \
            or transactions.get("headerId") != ident or not isinstance(transactions.get("transactions"), list) \
            or not transactions["transactions"]:
        raise ValueError("Checkpoint block is incomplete or inconsistent")
    if height == full and ident != node.get("bestFullHeaderId"):
        raise ValueError("Public tip changed while fetching the checkpoint; retry the check")
    if height == full and "stateRoot" in node and root != node["stateRoot"]:
        raise ValueError("Public checkpoint state root differs from its node tip")
    return validate_expected({"network": "testnet", "genesisBlockId": GENESIS,
                              "fullHeight": height, "headersHeight": height,
                              "bestFullHeaderId": ident, "stateRoot": root,
                              "chainRules": {"daaEpochLength": DAA_EPOCH, "votingEpochLength": VOTING_EPOCH}})


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_pin(value=None, repository=None):
    repository = repository or Path(__file__).resolve().parents[1]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    hex_pin(head, 40, "repository source commit")
    if value is not None and hex_pin(value, 40, "source commit") != head:
        raise ValueError("Source commit differs from the checked-out repository HEAD")
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"],
                                         cwd=repository, text=True).strip())
    return head, dirty


def epoch_evidence(height):
    height_pin(height)
    return {"daaEpochLength": DAA_EPOCH, "votingEpochLength": VOTING_EPOCH,
            "completeDaaDataEpochs": height // DAA_EPOCH,
            "completeVotingDataEpochs": height // VOTING_EPOCH,
            "executedDaaBoundaryTransitions": (height - 1) // DAA_EPOCH,
            "executedVotingBoundaryTransitions": (height - 1) // VOTING_EPOCH,
            "requiredMultipleEpochHeight": EPOCH_PROOF_HEIGHT,
            "multipleEpochHistoryVerified": height >= EPOCH_PROOF_HEIGHT,
            "daaAdaptationStressVerified": False}


def ingress_evidence(checks):
    endpoints = [set(check.get("dnsAddresses", [])) for check in checks]
    common = sorted(set.intersection(*endpoints)) if endpoints else []
    return {"commonDnsAddresses": common, "independentIngress": False,
            "independentInfrastructureVerified": False,
            "reason": "Separate ports and DNS addresses do not establish independent operators or failure domains"}
