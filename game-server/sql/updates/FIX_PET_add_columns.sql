ALTER TABLE `player_pets` 
	ADD `feed_progress` INT NOT NULL DEFAULT '0' AFTER `hungry_level`,
	ADD `dopings` VARCHAR(80) CHARACTER SET ascii NULL DEFAULT NULL AFTER `gift_cd_started`;