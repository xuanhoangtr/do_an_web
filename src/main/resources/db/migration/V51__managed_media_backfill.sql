-- Only provider identifiers already present in durable columns are backfilled.
INSERT INTO managed_media_objects (provider, resource_type, delivery_type, public_id,
                                   secure_url, purpose, status, retain_until)
SELECT 'CLOUDINARY', 'image', 'authenticated', e.private_public_id,
       NULL, 'RETURN_EVIDENCE', 'READY', CURRENT_TIMESTAMP + interval '730 days'
FROM return_evidence e
WHERE e.private_public_id IS NOT NULL AND e.private_public_id <> ''
ON CONFLICT (provider, resource_type, delivery_type, public_id) DO NOTHING;

INSERT INTO managed_media_objects (provider, resource_type, delivery_type, public_id,
                                   secure_url, purpose, status, retain_until)
SELECT 'CLOUDINARY', 'image', 'upload', m.public_id,
       NULL, 'REVIEW', 'READY', NULL
FROM product_review_media m
WHERE m.public_id IS NOT NULL AND m.public_id <> ''
ON CONFLICT (provider, resource_type, delivery_type, public_id) DO NOTHING;

INSERT INTO managed_media_objects (provider, resource_type, delivery_type, public_id,
                                   secure_url, purpose, status, retain_until)
SELECT 'CLOUDINARY', 'image', 'authenticated', f.delivery_proof_public_id,
       f.delivery_proof_url, 'DELIVERY_PROOF', 'READY',
       COALESCE(f.updated_at, f.created_at) + interval '3650 days'
FROM order_fulfillments f
WHERE f.delivery_proof_public_id IS NOT NULL AND f.delivery_proof_public_id <> ''
ON CONFLICT (provider, resource_type, delivery_type, public_id) DO NOTHING;

INSERT INTO managed_media_objects (provider, resource_type, delivery_type, public_id,
                                   secure_url, purpose, status, retain_until)
SELECT 'CLOUDINARY', 'image', 'authenticated', a.proof_public_id,
       a.proof_url, 'DELIVERY_PROOF', 'READY',
       a.created_at + interval '3650 days'
FROM delivery_attempts a
WHERE a.proof_public_id IS NOT NULL AND a.proof_public_id <> ''
ON CONFLICT (provider, resource_type, delivery_type, public_id) DO NOTHING;

UPDATE product_review_media m
SET managed_media_id = object.id
FROM managed_media_objects object
WHERE object.public_id = m.public_id AND object.purpose = 'REVIEW'
  AND m.public_id IS NOT NULL AND m.managed_media_id IS NULL;
UPDATE return_evidence e
SET managed_media_id = object.id
FROM managed_media_objects object
WHERE object.public_id = e.private_public_id AND object.purpose = 'RETURN_EVIDENCE'
  AND e.managed_media_id IS NULL;
UPDATE order_fulfillments f
SET delivery_proof_media_id = object.id
FROM managed_media_objects object
WHERE object.public_id = f.delivery_proof_public_id AND object.purpose = 'DELIVERY_PROOF'
  AND f.delivery_proof_media_id IS NULL;
UPDATE delivery_attempts a
SET proof_media_id = object.id
FROM managed_media_objects object
WHERE object.public_id = a.proof_public_id AND object.purpose = 'DELIVERY_PROOF'
  AND a.proof_media_id IS NULL;