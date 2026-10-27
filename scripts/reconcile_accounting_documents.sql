-- Report-only reconciliation for immutable accounting snapshots. Review these rows manually;
-- do not update historical documents automatically.

WITH captured AS (
    SELECT order_id, SUM(amount) AS captured_amount
    FROM payment_provider_transactions
    WHERE transaction_type = 'CAPTURE' AND status = 'SUCCEEDED'
    GROUP BY order_id
), cod AS (
    SELECT order_id, MAX(collected_amount) AS collected_amount
    FROM payments
    WHERE provider = 'COD'
    GROUP BY order_id
)
SELECT d.id AS document_id,
       d.document_number,
       d.order_id,
       d.document_type,
       d.payment_method,
       d.payment_reference,
       d.total_amount AS recorded_amount,
       CASE
           WHEN d.payment_method = 'COD' THEN cod.collected_amount
           ELSE captured.captured_amount
       END AS known_collected_amount,
       d.source_quality,
       d.issued_at
FROM accounting_documents d
LEFT JOIN captured ON captured.order_id = d.order_id
LEFT JOIN cod ON cod.order_id = d.order_id
WHERE d.document_type = 'PAYMENT_RECEIPT'
  AND CASE
          WHEN d.payment_method = 'COD' THEN cod.collected_amount
          ELSE captured.captured_amount
      END IS NOT NULL
  AND d.total_amount IS DISTINCT FROM CASE
          WHEN d.payment_method = 'COD' THEN cod.collected_amount
          ELSE captured.captured_amount
      END
ORDER BY d.issued_at, d.id;

WITH refund_transactions AS (
    SELECT order_id,
           COALESCE(provider_transaction_id, idempotency_key) AS reference,
           SUM(amount) AS refunded_amount,
           COUNT(*) AS matching_transactions
    FROM payment_provider_transactions
    WHERE transaction_type = 'REFUND' AND status = 'SUCCEEDED'
    GROUP BY order_id, COALESCE(provider_transaction_id, idempotency_key)
)
SELECT d.id AS document_id,
       d.document_number,
       d.order_id,
       d.payment_reference,
       d.total_amount AS recorded_amount,
       r.refunded_amount AS known_refunded_amount,
       r.matching_transactions
FROM accounting_documents d
LEFT JOIN refund_transactions r
       ON r.order_id = d.order_id AND r.reference = d.payment_reference
WHERE d.document_type = 'CREDIT_NOTE'
  AND (r.reference IS NULL OR d.total_amount IS DISTINCT FROM r.refunded_amount
       OR r.matching_transactions <> 1)
ORDER BY d.issued_at, d.id;
