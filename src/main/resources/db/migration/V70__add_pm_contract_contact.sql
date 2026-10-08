-- V70 - A PM site's contact, from its board's Phone and Email columns. The contact's
-- name is customer_name_raw (ชื่อลูกค้า), already mirrored. Shown to the team only;
-- never on a public link.
ALTER TABLE pm_contract ADD COLUMN contact_phone TEXT;
ALTER TABLE pm_contract ADD COLUMN contact_email TEXT;
