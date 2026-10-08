-- V71 - What a public link shows, in the words of the dialog it was made in ("Case ·
-- B01 Somewhere · Pudu 1", "PM site · …"), so the links page can list a case or a visit
-- by name rather than by an id. Null for links made before; the page describes those
-- from what they point at.
ALTER TABLE case_share_links ADD COLUMN title TEXT;
