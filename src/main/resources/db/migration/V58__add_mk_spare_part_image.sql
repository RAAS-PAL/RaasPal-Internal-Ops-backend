-- MK spare parts: one photo per part. Its own table so the stock list never loads image
-- bytes; the photo is fetched separately by the page that shows it.
--
-- Additive only.

CREATE TABLE mk_spare_part_image (
    part_id     UUID         PRIMARY KEY REFERENCES mk_spare_part(id),
    -- A base64 data:image/... URI, the same form RAAS PAL's own part photos use.
    image_data  TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
