UPDATE facilities SET access_hours = '24/7' WHERE access_hours IS NULL OR access_hours <> '24/7';
