package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"strconv"
)

func (c *client) bindRecoveryIdentity(prepared *preparedTransfer) (bool, error) {
	quote, err := c.signedQuote(prepared.Transaction, prepared.ID)
	if err != nil {
		return false, err
	}
	if quote == nil {
		if _, err := canonicalSignedTransaction(prepared.Transaction); err != nil {
			return false, err
		}
		return false, nil
	}
	paid, err := strconv.ParseInt(quote.Paid, 10, 64)
	if err != nil || paid != prepared.Fee {
		return false, errors.New("native transaction fee does not match saved recovery")
	}
	return true, nil // The quote ID is computed from native serialization, not copied from JSON's id field.
}

func bindObservedTransaction(prepared *preparedTransfer, observed json.RawMessage, nativeBound bool) error {
	var identifier struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(observed, &identifier) != nil || identifier.ID != prepared.ID {
		return errors.New("node returned a different observed transaction ID")
	}
	if nativeBound {
		return nil
	}
	expected, err := canonicalSignedTransaction(prepared.Transaction)
	if err != nil {
		return err
	}
	actual, err := canonicalSignedTransaction(observed)
	if err != nil {
		return err
	}
	if !bytes.Equal(expected, actual) {
		return errors.New("observed transaction does not match saved signed payload; recovery is preserved")
	}
	return nil
}

func canonicalSignedTransaction(raw json.RawMessage) ([]byte, error) {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var transaction map[string]any
	if err := decoder.Decode(&transaction); err != nil {
		return nil, errors.New("invalid signed transaction identity payload")
	}
	result := map[string]any{}
	for _, section := range []string{"inputs", "dataInputs", "outputs"} {
		items, ok := transaction[section].([]any)
		if !ok || (section != "dataInputs" && len(items) == 0) {
			return nil, errors.New("incomplete signed transaction identity arrays")
		}
		selected := []any{}
		for _, item := range items {
			object, ok := item.(map[string]any)
			if !ok {
				return nil, errors.New("invalid signed transaction identity entry")
			}
			fields := []string{"boxId"}
			if section == "inputs" {
				fields = append(fields, "spendingProof")
			}
			if section == "outputs" {
				fields = []string{"value", "ergoTree", "assets", "creationHeight", "additionalRegisters"}
			}
			entry := map[string]any{}
			for _, field := range fields {
				value, exists := object[field]
				if !exists || value == nil {
					return nil, errors.New("missing canonical signed transaction field")
				}
				entry[field] = value
			}
			if section == "inputs" {
				proof, ok := entry["spendingProof"].(map[string]any)
				if !ok {
					return nil, errors.New("invalid signed input proof")
				}
				proofBytes, bytesOK := proof["proofBytes"].(string)
				extension, extensionOK := proof["extension"].(map[string]any)
				if !bytesOK || !extensionOK {
					return nil, errors.New("incomplete signed input proof")
				}
				entry["spendingProof"] = map[string]any{"proofBytes": proofBytes, "extension": extension}
			}
			if section == "outputs" {
				if _, ok := entry["value"].(json.Number); !ok {
					return nil, errors.New("invalid signed output value")
				}
				if _, ok := entry["creationHeight"].(json.Number); !ok {
					return nil, errors.New("invalid signed output height")
				}
				if _, ok := entry["ergoTree"].(string); !ok {
					return nil, errors.New("invalid signed output script")
				}
				if _, ok := entry["assets"].([]any); !ok {
					return nil, errors.New("invalid signed output assets")
				}
				if _, ok := entry["additionalRegisters"].(map[string]any); !ok {
					return nil, errors.New("invalid signed output registers")
				}
			}
			selected = append(selected, entry)
		}
		result[section] = selected
	}
	return json.Marshal(result)
}
