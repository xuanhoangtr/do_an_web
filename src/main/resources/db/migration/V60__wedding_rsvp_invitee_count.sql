ALTER TABLE rsvps
    ADD COLUMN invitee_count INTEGER NOT NULL DEFAULT 1;

UPDATE rsvps
SET invitee_count = GREATEST(attendee_count, 1),
    attendee_count = CASE
        WHEN attendance_status = 'NOT_ATTENDING' THEN 0
        ELSE attendee_count
    END;

ALTER TABLE rsvps
    ADD CONSTRAINT ck_rsvps_attendee_counts
        CHECK (invitee_count >= 1 AND attendee_count >= 0 AND attendee_count <= invitee_count);
