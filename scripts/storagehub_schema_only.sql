CREATE DATABASE IF NOT EXISTS storagehub_local CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE storagehub_local;

-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: storagehub_local
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `activity_logs`
--

DROP TABLE IF EXISTS `activity_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `activity_logs` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `action` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `after_state_json` text COLLATE utf8mb4_unicode_ci,
  `before_state_json` text COLLATE utf8mb4_unicode_ci,
  `correlation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entity_id` binary(16) NOT NULL,
  `entity_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` binary(16) DEFAULT NULL,
  `facility_id` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK5wnh8r1e5ffup2wu2shpwywak` (`actor_id`),
  KEY `FKtbwww5dqhxbcb3781dtnbty8j` (`facility_id`),
  CONSTRAINT `FK5wnh8r1e5ffup2wu2shpwywak` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKtbwww5dqhxbcb3781dtnbty8j` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `auth_challenges`
--

DROP TABLE IF EXISTS `auth_challenges`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `auth_challenges` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `failed_attempts` int NOT NULL,
  `otp_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `purpose` enum('EMAIL_VERIFICATION','PASSWORD_RESET') COLLATE utf8mb4_unicode_ci NOT NULL,
  `token_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9uum04epvcifimaj7302cgomo` (`token_hash`),
  KEY `FKrt7wv87etoua39qe4nvn2uxia` (`user_id`),
  CONSTRAINT `FKrt7wv87etoua39qe4nvn2uxia` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `booking_documents`
--

DROP TABLE IF EXISTS `booking_documents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `booking_documents` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `document_type` enum('BOOKING_CONFIRMATION') COLLATE utf8mb4_unicode_ci NOT NULL,
  `issued_at` datetime(6) NOT NULL,
  `file_asset_id` binary(16) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKpj6xqtr1v26nx90yrslkw73kg` (`file_asset_id`),
  UNIQUE KEY `UKlpfky47upiyof06swpn01fn3g` (`reservation_id`),
  CONSTRAINT `FKn9a65y1fcecwn91cqhls212up` FOREIGN KEY (`file_asset_id`) REFERENCES `file_assets` (`id`),
  CONSTRAINT `FKqplkylb7ux6a73cqi1sugupxm` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `check_ins`
--

DROP TABLE IF EXISTS `check_ins`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `check_ins` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `checked_in_at` datetime(6) DEFAULT NULL,
  `checklist_json` varchar(8000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('cancelled','completed','no_show','rejected','scheduled') COLLATE utf8mb4_unicode_ci NOT NULL,
  `performed_by` binary(16) NOT NULL,
  `rental_id` binary(16) DEFAULT NULL,
  `reservation_id` binary(16) NOT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `readiness_note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `rejection_reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `rejection_disposition` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_check_in_reservation` (`reservation_id`),
  KEY `FKigrc3s2lkck53xc06wm4t05ra` (`performed_by`),
  KEY `FK5kttv3xaue27qxy5k3l1h4jc` (`rental_id`),
  CONSTRAINT `FK5kttv3xaue27qxy5k3l1h4jc` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`),
  CONSTRAINT `FKigrc3s2lkck53xc06wm4t05ra` FOREIGN KEY (`performed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKj7e1uts8j1uxm553w2ludh4mg` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `contracts`
--

DROP TABLE IF EXISTS `contracts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contracts` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `contract_number` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `signed_at` datetime(6) DEFAULT NULL,
  `signed_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('CANCELLED','DRAFT','EXPIRED','SENT','SIGNED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `rental_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKbx9jyu2cccdntb3ehrf0ojpfd` (`contract_number`),
  KEY `FKk4ahv9rydq6esn3x8vpw7sosk` (`rental_id`),
  CONSTRAINT `FKk4ahv9rydq6esn3x8vpw7sosk` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `facilities`
--

DROP TABLE IF EXISTS `facilities`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `facilities` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `city` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('active','coming_soon','inactive','maintenance') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmnsci7u7h2r2b3tohhn0b819` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `file_assets`
--

DROP TABLE IF EXISTS `file_assets`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_assets` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `checksum_sha256` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entity_id` binary(16) DEFAULT NULL,
  `entity_type` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `original_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `size_bytes` bigint NOT NULL,
  `status` enum('ACTIVE','DELETED','QUARANTINED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `storage_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `uploaded_by` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKjc97j6ckj052h8jo1qbvm8d5b` (`uploaded_by`),
  CONSTRAINT `FKjc97j6ckj052h8jo1qbvm8d5b` FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `script` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `login_history`
--

DROP TABLE IF EXISTS `login_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `login_history` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `email_attempted` varchar(320) COLLATE utf8mb4_unicode_ci NOT NULL,
  `failure_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ip_address` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `success` bit(1) NOT NULL,
  `user_agent` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` binary(16) DEFAULT NULL,
  `device_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_login_history_user_fp` (`user_id`,`success`,`device_fingerprint`),
  CONSTRAINT `FK20v0mimmdegh2afs39uixlxpm` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `maintenance_tasks`
--

DROP TABLE IF EXISTS `maintenance_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `maintenance_tasks` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `reason` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('cancelled','completed','in_progress','open') COLLATE utf8mb4_unicode_ci NOT NULL,
  `assigned_to` binary(16) DEFAULT NULL,
  `facility_id` binary(16) NOT NULL,
  `reported_by` binary(16) NOT NULL,
  `storage_unit_id` binary(16) NOT NULL,
  `damage_classification` enum('abandoned_goods','major_damage','minor_damage','no_damage') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `due_at` date DEFAULT NULL,
  `evidence_photos_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `priority` enum('high','low','medium') COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_report` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `return_case_id` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8mbaud59f9n86mcu7r9528mb6` (`assigned_to`),
  KEY `FKp7ilxr730tvwhl677lont0444` (`facility_id`),
  KEY `FKnuqrq7xjxkmsfquc4wukyq1ss` (`reported_by`),
  KEY `FKrrah15956g38vmhqxrijyxgo` (`storage_unit_id`),
  KEY `FKn5meemxhfbswkwpsrabcg1qfa` (`return_case_id`),
  CONSTRAINT `FK8mbaud59f9n86mcu7r9528mb6` FOREIGN KEY (`assigned_to`) REFERENCES `users` (`id`),
  CONSTRAINT `FKn5meemxhfbswkwpsrabcg1qfa` FOREIGN KEY (`return_case_id`) REFERENCES `return_cases` (`id`),
  CONSTRAINT `FKnuqrq7xjxkmsfquc4wukyq1ss` FOREIGN KEY (`reported_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKp7ilxr730tvwhl677lont0444` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`),
  CONSTRAINT `FKrrah15956g38vmhqxrijyxgo` FOREIGN KEY (`storage_unit_id`) REFERENCES `storage_units` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notifications`
--

DROP TABLE IF EXISTS `notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_read` bit(1) NOT NULL,
  `related_entity_id` binary(16) DEFAULT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` enum('CHECKIN','OVERDUE','PAYMENT','RESERVATION','RETURN','SUPPORT','SYSTEM') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9y21adhxn0ayjhfocscqox7bh` (`user_id`),
  CONSTRAINT `FK9y21adhxn0ayjhfocscqox7bh` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `overdue_follow_up_states`
--

DROP TABLE IF EXISTS `overdue_follow_up_states`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `overdue_follow_up_states` (
  `case_ref` varchar(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `revision` bigint NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`case_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `overdue_follow_ups`
--

DROP TABLE IF EXISTS `overdue_follow_ups`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `overdue_follow_ups` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `case_ref` varchar(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `rental_id` binary(16) NOT NULL,
  `type` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` binary(16) NOT NULL,
  `recorded_at` timestamp(6) NOT NULL,
  `external_ref` binary(16) DEFAULT NULL,
  `policy_ref` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `policy_version` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_overdue_follow_ups_rental` (`rental_id`),
  KEY `idx_overdue_follow_up_timeline` (`case_ref`,`recorded_at`),
  CONSTRAINT `fk_overdue_follow_ups_rental` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `payment_complaints`
--

DROP TABLE IF EXISTS `payment_complaints`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_complaints` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `decision_reason` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `review_due_at` datetime(6) NOT NULL,
  `reviewed_at` datetime(6) DEFAULT NULL,
  `status` enum('APPROVED','PENDING','REJECTED','REVIEW_OVERDUE','WITHDRAWN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `submitted_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  `customer_id` binary(16) NOT NULL,
  `payment_id` binary(16) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  `reviewed_by` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_complaint_reservation` (`reservation_id`),
  KEY `FK8opdl0co1ardxbtryshovnoxs` (`customer_id`),
  KEY `FKdwm5sn5ycx39d4ct3qmb0pj01` (`payment_id`),
  KEY `FK2025siww099o1co01v2iykcgr` (`reviewed_by`),
  CONSTRAINT `FK2025siww099o1co01v2iykcgr` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FK5ucyfrp3uuweharg7ktajgqjc` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`),
  CONSTRAINT `FK8opdl0co1ardxbtryshovnoxs` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKdwm5sn5ycx39d4ct3qmb0pj01` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `payments`
--

DROP TABLE IF EXISTS `payments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payments` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` decimal(14,2) NOT NULL,
  `currency` varchar(3) COLLATE utf8mb4_unicode_ci NOT NULL,
  `failure_code` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failure_reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `idempotency_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  `purpose` enum('DAMAGE_FEE','MONTHLY_RENT','OTHER','RENEWAL','RENTAL_BALANCE','RESERVATION_DEPOSIT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('CANCELLED','FAILED','NOT_RECEIVED','PAID','PENDING','PROCESSING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL,
  `initiated_by` binary(16) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  `gateway_intent_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKj4pdqn1207fuhx2gybp1ue799` (`idempotency_key`),
  KEY `FK8mj08peqy83xpb78j5gdc827a` (`initiated_by`),
  KEY `FKp8yh4sjt3u0g6aru1oxfh3o14` (`reservation_id`),
  CONSTRAINT `FK8mj08peqy83xpb78j5gdc827a` FOREIGN KEY (`initiated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKp8yh4sjt3u0g6aru1oxfh3o14` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `permissions`
--

DROP TABLE IF EXISTS `permissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `permissions` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK7lcb6glmvwlro3p2w2cewxtvd` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `refunds`
--

DROP TABLE IF EXISTS `refunds`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refunds` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` decimal(14,2) NOT NULL,
  `gateway_refund_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('CANCELLED','COMPLETED','FAILED','PENDING','PROCESSING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `payment_id` binary(16) NOT NULL,
  `requested_by` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpt9ic0j1y6xwlej99wnynvnpy` (`payment_id`),
  KEY `FK1normc5g6it6fpqbrklfxmert` (`requested_by`),
  CONSTRAINT `FK1normc5g6it6fpqbrklfxmert` FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKpt9ic0j1y6xwlej99wnynvnpy` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_accepted_revisions`
--

DROP TABLE IF EXISTS `renewal_accepted_revisions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_accepted_revisions` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `renewal_id` binary(16) NOT NULL,
  `quote_id` binary(16) NOT NULL,
  `revision_number` int NOT NULL,
  `accepted_at` timestamp(6) NOT NULL,
  `note` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_renewal_revision` (`renewal_id`,`revision_number`),
  UNIQUE KEY `uk_renewal_accepted_quote` (`quote_id`),
  CONSTRAINT `fk_renewal_accepted_revisions_quote` FOREIGN KEY (`quote_id`) REFERENCES `renewal_quotes` (`id`),
  CONSTRAINT `fk_renewal_accepted_revisions_renewal` FOREIGN KEY (`renewal_id`) REFERENCES `renewals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_idempotency`
--

DROP TABLE IF EXISTS `renewal_idempotency`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_idempotency` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `actor_id` binary(16) NOT NULL,
  `operation` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_key_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_id` binary(16) NOT NULL,
  `payload_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `http_status` int NOT NULL,
  `result_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_renewal_idempotency` (`actor_id`,`operation`,`request_key_hash`),
  CONSTRAINT `fk_renewal_idempotency_actor` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_open_slots`
--

DROP TABLE IF EXISTS `renewal_open_slots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_open_slots` (
  `id` binary(16) NOT NULL,
  `renewal_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_renewal_open_slot_renewal` (`renewal_id`),
  CONSTRAINT `fk_renewal_open_slots_renewal` FOREIGN KEY (`renewal_id`) REFERENCES `renewals` (`id`),
  CONSTRAINT `fk_renewal_open_slots_rental` FOREIGN KEY (`id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_operation_events`
--

DROP TABLE IF EXISTS `renewal_operation_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_operation_events` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `renewal_id` binary(16) NOT NULL,
  `kind` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `occurred_at` timestamp(6) NOT NULL,
  `actor_id` binary(16) DEFAULT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_renewal_event_timeline` (`renewal_id`,`kind`,`occurred_at`),
  CONSTRAINT `fk_renewal_operation_events_renewal` FOREIGN KEY (`renewal_id`) REFERENCES `renewals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_operation_states`
--

DROP TABLE IF EXISTS `renewal_operation_states`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_operation_states` (
  `id` binary(16) NOT NULL,
  `phase` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `deposit_payment_ref` binary(16) DEFAULT NULL,
  `deposit_paid_at` timestamp(6) NULL DEFAULT NULL,
  `original_signing_deadline` timestamp(6) NULL DEFAULT NULL,
  `effective_signing_deadline` timestamp(6) NULL DEFAULT NULL,
  `recovery_cutoff` timestamp(6) NULL DEFAULT NULL,
  `appointment_ref` binary(16) DEFAULT NULL,
  `appointment_start` timestamp(6) NULL DEFAULT NULL,
  `appointment_end` timestamp(6) NULL DEFAULT NULL,
  `arrival_ref` binary(16) DEFAULT NULL,
  `confirmed_exception_ref` binary(16) DEFAULT NULL,
  `pending_exception_ref` binary(16) DEFAULT NULL,
  `completed_by` binary(16) DEFAULT NULL,
  `completed_at` timestamp(6) NULL DEFAULT NULL,
  `policy_ref` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `policy_version` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_renewal_operation_states_renewal` FOREIGN KEY (`id`) REFERENCES `renewals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_quotes`
--

DROP TABLE IF EXISTS `renewal_quotes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_quotes` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `rental_id` binary(16) NOT NULL,
  `customer_id` binary(16) NOT NULL,
  `terms_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `terms_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quoted_at` timestamp(6) NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_renewal_quotes_rental` (`rental_id`),
  KEY `fk_renewal_quotes_customer` (`customer_id`),
  CONSTRAINT `fk_renewal_quotes_customer` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_renewal_quotes_rental` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewal_workflows`
--

DROP TABLE IF EXISTS `renewal_workflows`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewal_workflows` (
  `id` binary(16) NOT NULL,
  `version` bigint NOT NULL,
  `accepted_revision_id` binary(16) NOT NULL,
  `reviewer_id` binary(16) DEFAULT NULL,
  `reviewed_at` timestamp(6) NULL DEFAULT NULL,
  `review_reason` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cancellation_reason` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_deadline` timestamp(6) NULL DEFAULT NULL,
  `extension_hold_ref` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_renewal_workflows_revision` (`accepted_revision_id`),
  KEY `fk_renewal_workflows_reviewer` (`reviewer_id`),
  CONSTRAINT `fk_renewal_workflows_renewal` FOREIGN KEY (`id`) REFERENCES `renewals` (`id`),
  CONSTRAINT `fk_renewal_workflows_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_renewal_workflows_revision` FOREIGN KEY (`accepted_revision_id`) REFERENCES `renewal_accepted_revisions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `renewals`
--

DROP TABLE IF EXISTS `renewals`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `renewals` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` decimal(14,2) NOT NULL,
  `new_end_date` date NOT NULL,
  `status` enum('appointment_scheduled','approved','cancelled','completed','deposit_paid','payment_expired','payment_failed','payment_processing','pending','rejected') COLLATE utf8mb4_unicode_ci NOT NULL,
  `rental_id` binary(16) NOT NULL,
  `requested_by` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKs83xnlsxcd26dhjdy3il403e9` (`rental_id`),
  KEY `FKnxgqvsbe1nkhag37jwmws6oob` (`requested_by`),
  CONSTRAINT `FKnxgqvsbe1nkhag37jwmws6oob` FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKs83xnlsxcd26dhjdy3il403e9` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `rental_package_policies`
--

DROP TABLE IF EXISTS `rental_package_policies`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rental_package_policies` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `active` bit(1) NOT NULL,
  `code` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `discount_rate` decimal(5,4) NOT NULL,
  `effective_from` date NOT NULL,
  `effective_to` date DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `policy_version` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `rental_months` int NOT NULL,
  `facility_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rental_package_facility_code` (`facility_id`,`code`),
  CONSTRAINT `FKd9e8jlcccuthclwifi6nkogbn` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `rentals`
--

DROP TABLE IF EXISTS `rentals`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rentals` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `actual_returned_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `contract_end_date` date NOT NULL,
  `monthly_price` decimal(14,2) NOT NULL,
  `start_date` date NOT NULL,
  `status` enum('active','closing','completed','return_inspection','return_requested') COLLATE utf8mb4_unicode_ci NOT NULL,
  `customer_id` binary(16) NOT NULL,
  `facility_id` binary(16) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  `storage_unit_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKevwks1tm1ubio8i4ewq4g2imj` (`customer_id`),
  KEY `FKirga5uug386g7gprr4ggra2gg` (`facility_id`),
  KEY `FKdvgvnftw7jt5uca6f666wyjm3` (`reservation_id`),
  KEY `FKto0msblggo7awqsqlpkcgnuyt` (`storage_unit_id`),
  CONSTRAINT `FKdvgvnftw7jt5uca6f666wyjm3` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`),
  CONSTRAINT `FKevwks1tm1ubio8i4ewq4g2imj` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKirga5uug386g7gprr4ggra2gg` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`),
  CONSTRAINT `FKto0msblggo7awqsqlpkcgnuyt` FOREIGN KEY (`storage_unit_id`) REFERENCES `storage_units` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reservation_email_verifications`
--

DROP TABLE IF EXISTS `reservation_email_verifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reservation_email_verifications` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `failed_attempts` int NOT NULL,
  `last_sent_at` datetime(6) NOT NULL,
  `otp_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resend_count` int NOT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  `reservation_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKs77811a81jj8icnn37iy1w02x` (`reservation_id`),
  CONSTRAINT `FKq3opnmgsfrmvx163isqlf9o9m` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reservation_goods_items`
--

DROP TABLE IF EXISTS `reservation_goods_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reservation_goods_items` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `category` enum('CAMERA_EQUIPMENT','CERAMIC_GLASS','DECOR','ELECTRONICS','EVENT_EQUIPMENT','FINE_ART','FURNITURE','GIFTS','KITCHENWARE','MOVING_ITEMS','MUSICAL_INSTRUMENTS','OFFICE','OTHER','SPORTS','STORE_FIXTURES','TOYS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `custom_goods_name` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `custom_material` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fragile` bit(1) NOT NULL,
  `height_cm` decimal(10,2) NOT NULL,
  `length_cm` decimal(10,2) NOT NULL,
  `material_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `quantity` int NOT NULL,
  `requires_staff_review` bit(1) NOT NULL,
  `review_note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `review_status` enum('APPROVED','NOT_REQUIRED','PENDING','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `weight_kg` decimal(10,2) NOT NULL,
  `width_cm` decimal(10,2) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKif2cuuctqwrl6p9e8f6xn6mei` (`reservation_id`),
  CONSTRAINT `FKif2cuuctqwrl6p9e8f6xn6mei` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reservation_pricing_snapshots`
--

DROP TABLE IF EXISTS `reservation_pricing_snapshots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reservation_pricing_snapshots` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `discount_amount` decimal(14,2) NOT NULL,
  `discount_rate` decimal(5,4) NOT NULL,
  `due_at_check_in` decimal(14,2) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `gross_rental_amount` decimal(14,2) NOT NULL,
  `monthly_price` decimal(14,2) NOT NULL,
  `net_rental_amount` decimal(14,2) NOT NULL,
  `pricing_package_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pricing_policy_version` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quoted_at` datetime(6) NOT NULL,
  `remaining_rental_amount` decimal(14,2) NOT NULL,
  `rental_months` int NOT NULL,
  `reservation_deposit_amount` decimal(14,2) NOT NULL,
  `security_deposit_amount` decimal(14,2) NOT NULL,
  `total_initial_obligation` decimal(14,2) NOT NULL,
  `reservation_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK2sl8h0riborv0oggpo9hxc2cw` (`reservation_id`),
  CONSTRAINT `FKex6t75amii2qth3mhtpsaaqn6` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reservation_quotes`
--

DROP TABLE IF EXISTS `reservation_quotes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reservation_quotes` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `discount_amount` decimal(14,2) NOT NULL,
  `discount_rate` decimal(5,4) NOT NULL,
  `due_at_check_in` decimal(14,2) NOT NULL,
  `end_date` date NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `monthly_price` decimal(14,2) NOT NULL,
  `policy_version` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pricing_package_code` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quoted_at` datetime(6) NOT NULL,
  `remaining_rental_amount` decimal(14,2) NOT NULL,
  `rental_months` int NOT NULL,
  `reservation_deposit_amount` decimal(14,2) NOT NULL,
  `security_deposit_amount` decimal(14,2) NOT NULL,
  `start_date` date NOT NULL,
  `subtotal` decimal(14,2) NOT NULL,
  `total_after_discount` decimal(14,2) NOT NULL,
  `total_initial_obligation` decimal(14,2) NOT NULL,
  `customer_id` binary(16) NOT NULL,
  `facility_id` binary(16) NOT NULL,
  `unit_type_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKhjye1e4v5qvvl7ksvnn2o95wf` (`customer_id`),
  KEY `FKf9ixp6s0rsykhq1i0x61t3ph4` (`facility_id`),
  KEY `FKso5wghrj3jobx8xskg7bl0up2` (`unit_type_id`),
  CONSTRAINT `FKf9ixp6s0rsykhq1i0x61t3ph4` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`),
  CONSTRAINT `FKhjye1e4v5qvvl7ksvnn2o95wf` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKso5wghrj3jobx8xskg7bl0up2` FOREIGN KEY (`unit_type_id`) REFERENCES `unit_types` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reservations`
--

DROP TABLE IF EXISTS `reservations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reservations` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` decimal(14,2) NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `cancel_reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `compatibility_result` enum('COMPATIBLE','INCOMPATIBLE','PENDING','REVIEW_REQUIRED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `complaint_expires_at` datetime(6) DEFAULT NULL,
  `confirmed_at` datetime(6) DEFAULT NULL,
  `deposit_paid_at` datetime(6) DEFAULT NULL,
  `end_date` date NOT NULL,
  `expired_at` datetime(6) DEFAULT NULL,
  `goods_condition` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `goods_review_due_at` datetime(6) DEFAULT NULL,
  `goods_review_note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `goods_review_status` enum('APPROVED','NOT_REQUIRED','PENDING','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `goods_review_submitted_at` datetime(6) DEFAULT NULL,
  `goods_reviewed_at` datetime(6) DEFAULT NULL,
  `hold_expires_at` datetime(6) DEFAULT NULL,
  `idempotency_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `notes` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_expires_at` datetime(6) DEFAULT NULL,
  `rejected_at` datetime(6) DEFAULT NULL,
  `rejection_reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reservation_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `start_date` date NOT NULL,
  `status` enum('AWAITING_CUSTOMER_RECEIPT','AWAITING_EMAIL','AWAITING_PAYMENT','AWAITING_REVIEW','CANCELLED','COMPLETED','CONFIRMED','EXPIRED','PAYMENT_GRACE','PAYMENT_REVIEW','READY_FOR_CHECKIN','REJECTED','UNIT_RESERVED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_goods_volume_m3` decimal(16,6) NOT NULL,
  `total_goods_weight_kg` decimal(14,2) NOT NULL,
  `version` bigint NOT NULL,
  `assigned_unit_id` binary(16) DEFAULT NULL,
  `customer_id` binary(16) NOT NULL,
  `facility_id` binary(16) NOT NULL,
  `goods_reviewed_by` binary(16) DEFAULT NULL,
  `source_quote_id` binary(16) NOT NULL,
  `unit_type_id` binary(16) NOT NULL,
  `request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_customer_idempotency` (`customer_id`,`idempotency_key`),
  UNIQUE KEY `UK6g1uj544xgjuyhj6kjh6pka6l` (`reservation_code`),
  KEY `FKsdsg703b1ywr54l599wbb885l` (`assigned_unit_id`),
  KEY `FKd5gn5n5tnrf5l16o9koaqxsok` (`facility_id`),
  KEY `FKdpw899dseek03nkunsxh841p5` (`goods_reviewed_by`),
  KEY `FKsuabp2gav9fbr3n6bd8onie40` (`source_quote_id`),
  KEY `FKn5p54gsybo7um2xj9mpiwtnq0` (`unit_type_id`),
  CONSTRAINT `FKcmkyuub3ieebwbnrvh5710ply` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKd5gn5n5tnrf5l16o9koaqxsok` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`),
  CONSTRAINT `FKdpw899dseek03nkunsxh841p5` FOREIGN KEY (`goods_reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKn5p54gsybo7um2xj9mpiwtnq0` FOREIGN KEY (`unit_type_id`) REFERENCES `unit_types` (`id`),
  CONSTRAINT `FKsdsg703b1ywr54l599wbb885l` FOREIGN KEY (`assigned_unit_id`) REFERENCES `storage_units` (`id`),
  CONSTRAINT `FKsuabp2gav9fbr3n6bd8onie40` FOREIGN KEY (`source_quote_id`) REFERENCES `reservation_quotes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `return_cases`
--

DROP TABLE IF EXISTS `return_cases`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `return_cases` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `damage_classification` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `inspection_notes` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `refund_amount` decimal(14,2) DEFAULT NULL,
  `scheduled_at` datetime(6) DEFAULT NULL,
  `status` enum('awaiting_customer_confirmation','completed','disputed','inspected','payment_due','refund_pending','requested','scheduled') COLLATE utf8mb4_unicode_ci NOT NULL,
  `rental_id` binary(16) NOT NULL,
  `requested_by` binary(16) NOT NULL,
  `amount_due_from_customer` decimal(14,2) NOT NULL,
  `cleaning_fee` decimal(14,2) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `customer_confirmed` bit(1) NOT NULL,
  `customer_confirmed_at` datetime(6) DEFAULT NULL,
  `customer_decision` enum('accepted','disputed') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_decision_note` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_notes` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `damage_fee` decimal(14,2) NOT NULL,
  `deposit_amount` decimal(14,2) NOT NULL,
  `evidence_photos_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `inspected_at` datetime(6) DEFAULT NULL,
  `inventory_match` enum('excess','match','missing') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lost_item_fee` decimal(14,2) NOT NULL,
  `manager_resolution_note` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `net_refund_amount` decimal(14,2) NOT NULL,
  `outstanding_fee` decimal(14,2) NOT NULL,
  `overdue_days` int DEFAULT NULL,
  `overdue_fee` decimal(14,2) NOT NULL,
  `proposed_unit_status` enum('assigned','available','held','maintenance','occupied','reserved') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `requested_at` datetime(6) NOT NULL,
  `returned_card` bit(1) NOT NULL,
  `returned_key` bit(1) NOT NULL,
  `returned_lock` bit(1) NOT NULL,
  `reviewed_at` datetime(6) DEFAULT NULL,
  `scheduled_date` date NOT NULL,
  `settlement_paid_at` datetime(6) DEFAULT NULL,
  `settlement_payment_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_deductions` decimal(14,2) NOT NULL,
  `customer_id` binary(16) NOT NULL,
  `facility_id` binary(16) NOT NULL,
  `inspected_by` binary(16) DEFAULT NULL,
  `reviewed_by` binary(16) DEFAULT NULL,
  `storage_unit_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtc56eylhn6wqwhwh7k6buqeen` (`rental_id`),
  KEY `FKs6qbestbfpd2chg428i5qap15` (`requested_by`),
  KEY `FKmgku7q3fabixxhj3irixeufgq` (`customer_id`),
  KEY `FKt5ljfikbriqeb9ttbnc5ejhg7` (`facility_id`),
  KEY `FK3pl892nc9e55s3na5w371dj8n` (`inspected_by`),
  KEY `FKlsv0jp1ums7yx34r9cntpth0m` (`reviewed_by`),
  KEY `FK15e40kd3clpodpiwhkgskr6ug` (`storage_unit_id`),
  CONSTRAINT `FK15e40kd3clpodpiwhkgskr6ug` FOREIGN KEY (`storage_unit_id`) REFERENCES `storage_units` (`id`),
  CONSTRAINT `FK3pl892nc9e55s3na5w371dj8n` FOREIGN KEY (`inspected_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKlsv0jp1ums7yx34r9cntpth0m` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKmgku7q3fabixxhj3irixeufgq` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKs6qbestbfpd2chg428i5qap15` FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKt5ljfikbriqeb9ttbnc5ejhg7` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`),
  CONSTRAINT `FKtc56eylhn6wqwhwh7k6buqeen` FOREIGN KEY (`rental_id`) REFERENCES `rentals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `role_permissions`
--

DROP TABLE IF EXISTS `role_permissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `role_permissions` (
  `role_id` binary(16) NOT NULL,
  `permission_id` binary(16) NOT NULL,
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `FKegdk29eiy7mdtefy5c7eirr6e` (`permission_id`),
  CONSTRAINT `FKegdk29eiy7mdtefy5c7eirr6e` FOREIGN KEY (`permission_id`) REFERENCES `permissions` (`id`),
  CONSTRAINT `FKn5fotdgk8d1xvo8nav9uv3muc` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roles` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` enum('ADMIN','BUSINESS','CUSTOMER','MANAGER','STAFF') COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parent_role_id` binary(16) DEFAULT NULL,
  `permissions_policy_version` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKch1113horj4qr56f91omojv8` (`code`),
  KEY `fk_roles_parent_role` (`parent_role_id`),
  CONSTRAINT `fk_roles_parent_role` FOREIGN KEY (`parent_role_id`) REFERENCES `roles` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sessions`
--

DROP TABLE IF EXISTS `sessions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sessions` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `created_ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `last_seen_at` datetime(6) DEFAULT NULL,
  `refresh_expires_at` datetime(6) DEFAULT NULL,
  `refresh_token_hash` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `token_hash` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_agent` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKcairj5kyeqdedpai90prd84m5` (`refresh_token_hash`),
  UNIQUE KEY `UK28ho0kcbmgitseqse6h73976d` (`token_hash`),
  KEY `FKruie73rneumyyd1bgo6qw8vjt` (`user_id`),
  CONSTRAINT `FKruie73rneumyyd1bgo6qw8vjt` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `storage_units`
--

DROP TABLE IF EXISTS `storage_units`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `storage_units` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `available_from` datetime(6) DEFAULT NULL,
  `code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `floor` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_released_at` datetime(6) DEFAULT NULL,
  `status` enum('assigned','available','held','maintenance','occupied','reserved') COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL,
  `zone` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `facility_id` binary(16) NOT NULL,
  `unit_type_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK3joxa570umshcvtxys3cimyuq` (`code`),
  KEY `FKpby5f37w45li5g2x18jdu2lv5` (`facility_id`),
  KEY `FKhnjapnod3njbfamhj8l38ke5l` (`unit_type_id`),
  CONSTRAINT `FKhnjapnod3njbfamhj8l38ke5l` FOREIGN KEY (`unit_type_id`) REFERENCES `unit_types` (`id`),
  CONSTRAINT `FKpby5f37w45li5g2x18jdu2lv5` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_command_receipts`
--

DROP TABLE IF EXISTS `support_command_receipts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_command_receipts` (
  `id` binary(16) NOT NULL,
  `actor_id` binary(16) NOT NULL,
  `operation` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `key_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_support_actor_command_key` (`actor_id`,`operation`,`key_hash`),
  UNIQUE KEY `UKb944lqjpb5ntcf5av96js4y28` (`actor_id`,`operation`,`key_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_escalations`
--

DROP TABLE IF EXISTS `support_escalations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_escalations` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `ticket_id` binary(16) NOT NULL,
  `target_module` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `decision_reason` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `requested_by` binary(16) DEFAULT NULL,
  `decided_by` binary(16) DEFAULT NULL,
  `receiver_ref` binary(16) DEFAULT NULL,
  `requested_at` timestamp(6) NULL DEFAULT NULL,
  `decided_at` timestamp(6) NULL DEFAULT NULL,
  `evidence_json` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_support_escalations_ticket` (`ticket_id`),
  CONSTRAINT `fk_support_escalations_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_messages`
--

DROP TABLE IF EXISTS `support_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_messages` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `ticket_id` binary(16) NOT NULL,
  `author_id` binary(16) NOT NULL,
  `author_role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `visibility` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `body` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence_json` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sent_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_support_messages_ticket` (`ticket_id`),
  CONSTRAINT `fk_support_messages_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_ticket_messages`
--

DROP TABLE IF EXISTS `support_ticket_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_ticket_messages` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `ticket_id` binary(16) NOT NULL,
  `author_id` binary(16) NOT NULL,
  `body` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_support_ticket_messages_author` (`author_id`),
  KEY `idx_support_ticket_messages_ticket_created` (`ticket_id`,`created_at`),
  CONSTRAINT `fk_support_ticket_messages_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_support_ticket_messages_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_tickets`
--

DROP TABLE IF EXISTS `support_tickets`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_tickets` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `description` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('closed','in_progress','open','resolved','waiting_customer') COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assigned_to` binary(16) DEFAULT NULL,
  `customer_id` binary(16) NOT NULL,
  `facility_id` binary(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8dtobc1tqyif0xuv09uty3eht` (`assigned_to`),
  KEY `idx_support_tickets_customer_status` (`customer_id`,`status`,`updated_at`),
  KEY `idx_support_tickets_facility_status` (`facility_id`,`status`,`updated_at`),
  CONSTRAINT `FK1tmbsselat6ceyejefrvdeis9` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FK8dtobc1tqyif0xuv09uty3eht` FOREIGN KEY (`assigned_to`) REFERENCES `users` (`id`),
  CONSTRAINT `FKmfreaecg9u86fmf0xh7oqpm0s` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_workflow_events`
--

DROP TABLE IF EXISTS `support_workflow_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_workflow_events` (
  `id` binary(16) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `ticket_id` binary(16) NOT NULL,
  `type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` binary(16) DEFAULT NULL,
  `assigned_staff_id` binary(16) DEFAULT NULL,
  `assignment_revision` bigint NOT NULL,
  `reason` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `recorded_at` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_support_workflow_events_ticket` (`ticket_id`),
  CONSTRAINT `fk_support_workflow_events_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `support_workflow_states`
--

DROP TABLE IF EXISTS `support_workflow_states`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `support_workflow_states` (
  `id` binary(16) NOT NULL,
  `version` bigint NOT NULL,
  `assignment_revision` bigint NOT NULL,
  `change_sequence` bigint NOT NULL,
  `accepted_by` binary(16) DEFAULT NULL,
  `assigned_at` timestamp(6) NULL DEFAULT NULL,
  `accepted_at` timestamp(6) NULL DEFAULT NULL,
  `resolved_by` binary(16) DEFAULT NULL,
  `resolved_at` timestamp(6) NULL DEFAULT NULL,
  `closed_at` timestamp(6) NULL DEFAULT NULL,
  `last_public_staff_reply_at` timestamp(6) NULL DEFAULT NULL,
  `first_public_staff_reply_at` timestamp(6) NULL DEFAULT NULL,
  `parent_ticket_id` binary(16) DEFAULT NULL,
  `linked_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `linked_id` binary(16) DEFAULT NULL,
  `changed_at` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_support_workflow_states_ticket` FOREIGN KEY (`id`) REFERENCES `support_tickets` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `system_settings`
--

DROP TABLE IF EXISTS `system_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_settings` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `label` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `options_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `setting_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `setting_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `setting_value` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKnm18l4pyovtvd8y3b3x0l2y64` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `unit_assignments`
--

DROP TABLE IF EXISTS `unit_assignments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `unit_assignments` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `assigned_at` datetime(6) NOT NULL,
  `cancel_reason` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cancellation_idempotency_key` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cancellation_request_fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `previous_storage_unit_status` enum('assigned','available','held','maintenance','occupied','reserved') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `release_disposition` enum('AVAILABLE','MAINTENANCE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('ACTIVE','CANCELLED','COMPLETED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL,
  `assigned_by` binary(16) NOT NULL,
  `cancelled_by` binary(16) DEFAULT NULL,
  `reservation_id` binary(16) NOT NULL,
  `storage_unit_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unit_assignment_cancel_actor_key` (`cancelled_by`,`cancellation_idempotency_key`),
  KEY `FK4luc7p9aw0i03u721eqm74su9` (`assigned_by`),
  KEY `FKtmi7efeuhmmrn6sead0q47y9c` (`reservation_id`),
  KEY `FK4jhxtob6l961g2wa0ci91n2c4` (`storage_unit_id`),
  CONSTRAINT `FK4jhxtob6l961g2wa0ci91n2c4` FOREIGN KEY (`storage_unit_id`) REFERENCES `storage_units` (`id`),
  CONSTRAINT `FK4luc7p9aw0i03u721eqm74su9` FOREIGN KEY (`assigned_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FK4q466sceb82c4rlv0d6veyv25` FOREIGN KEY (`cancelled_by`) REFERENCES `users` (`id`),
  CONSTRAINT `FKtmi7efeuhmmrn6sead0q47y9c` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `unit_types`
--

DROP TABLE IF EXISTS `unit_types`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `unit_types` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `height_m` decimal(10,2) NOT NULL,
  `image_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `length_m` decimal(10,2) NOT NULL,
  `max_load_kg` decimal(10,2) NOT NULL,
  `monthly_price` decimal(14,2) NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `rack_count` int NOT NULL,
  `rack_height_m` decimal(10,2) NOT NULL,
  `rack_length_m` decimal(10,2) NOT NULL,
  `rack_width_m` decimal(10,2) NOT NULL,
  `status` enum('active','inactive') COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL,
  `width_m` decimal(10,2) NOT NULL,
  `facility_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unit_type_facility_code` (`facility_id`,`code`),
  CONSTRAINT `FKhxofa0ks76rlh3uftn89vkvmn` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_facility_scopes`
--

DROP TABLE IF EXISTS `user_facility_scopes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_facility_scopes` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `scope_level` enum('MANAGE','OPERATE','READ') COLLATE utf8mb4_unicode_ci NOT NULL,
  `facility_id` binary(16) NOT NULL,
  `user_id` binary(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKilfncrt0jjj3lenklvw8acdhj` (`user_id`,`facility_id`),
  KEY `FKljddhs1pqqwjg2okxng25tn21` (`facility_id`),
  CONSTRAINT `FK409bu1a91682vdyl01acn0ye6` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKljddhs1pqqwjg2okxng25tn21` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_roles`
--

DROP TABLE IF EXISTS `user_roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_roles` (
  `user_id` binary(16) NOT NULL,
  `role_id` binary(16) NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `FKh8ciramu9cc9q3qcqiv4ue8a6` (`role_id`),
  CONSTRAINT `FKh8ciramu9cc9q3qcqiv4ue8a6` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`),
  CONSTRAINT `FKhfh9dx7w3ubf1co1vdev94g3f` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` binary(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `avatar_url` longtext COLLATE utf8mb4_unicode_ci,
  `email` varchar(320) COLLATE utf8mb4_unicode_ci NOT NULL,
  `full_name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,
  `must_change_password` tinyint(1) NOT NULL DEFAULT '0',
  `password_hash` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `phone` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('ACTIVE','DISABLED','INACTIVE','LOCKED','PENDING_VERIFICATION','SUSPENDED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `emergency_contact_name` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `emergency_contact_phone` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `permanent_address` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'storagehub_local'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-11 18:42:23
