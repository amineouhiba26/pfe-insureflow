-- Migration V3: Add and update human review task columns for better administrative tracking
-- Existing 'adjuster_notes' will be phased out in favor of 'resolution_note'

ALTER TABLE human_review_tasks
  ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'PENDING',
  ADD COLUMN IF NOT EXISTS resolution_note TEXT;

-- Optional: Copy existing data if any (safeguard)
UPDATE human_review_tasks
SET resolution_note = adjuster_notes
WHERE resolution_note IS NULL AND adjuster_notes IS NOT NULL;
