-- HD- + up to 64 characters of unit code + '-' + 8 characters of rental ID.
ALTER TABLE contracts MODIFY COLUMN contract_number VARCHAR(76) NOT NULL;
