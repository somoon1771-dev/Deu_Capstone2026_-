-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: busan_db
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `building_features`
--

DROP TABLE IF EXISTS `building_features`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `building_features` (
  `feature_key` varchar(512) NOT NULL,
  `min_lat` double DEFAULT NULL,
  `min_lon` double DEFAULT NULL,
  `max_lat` double DEFAULT NULL,
  `max_lon` double DEFAULT NULL,
  `feature_json` longtext,
  PRIMARY KEY (`feature_key`),
  KEY `idx_building_features_bbox` (`min_lat`,`max_lat`,`min_lon`,`max_lon`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `building_tile_features`
--

DROP TABLE IF EXISTS `building_tile_features`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `building_tile_features` (
  `cache_key` varchar(128) NOT NULL,
  `feature_key` varchar(512) NOT NULL,
  PRIMARY KEY (`cache_key`,`feature_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `building_tiles`
--

DROP TABLE IF EXISTS `building_tiles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `building_tiles` (
  `cache_key` varchar(128) NOT NULL,
  `min_lat` double DEFAULT NULL,
  `min_lon` double DEFAULT NULL,
  `max_lat` double DEFAULT NULL,
  `max_lon` double DEFAULT NULL,
  `truncated` tinyint(1) DEFAULT NULL,
  `fetched_at` bigint DEFAULT NULL,
  PRIMARY KEY (`cache_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `busan_general_cctv`
--

DROP TABLE IF EXISTS `busan_general_cctv`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `busan_general_cctv` (
  `id` int NOT NULL AUTO_INCREMENT,
  `district` varchar(50) DEFAULT NULL COMMENT '구군',
  `address` varchar(255) DEFAULT NULL COMMENT '도로명 주소',
  `jibun_address` varchar(255) DEFAULT NULL COMMENT '구(지번)주소',
  `purpose` varchar(100) DEFAULT NULL COMMENT '설치목적',
  `qty` int DEFAULT NULL COMMENT '수량',
  `install_ym` varchar(20) DEFAULT NULL COMMENT '설치일자',
  `lat` double DEFAULT NULL COMMENT '위도',
  `lon` double DEFAULT NULL COMMENT '경도',
  `geom` geometry NOT NULL /*!80003 SRID 5186 */ COMMENT '5186 평면 공간좌표',
  PRIMARY KEY (`id`),
  SPATIAL KEY `geom` (`geom`)
) ENGINE=InnoDB AUTO_INCREMENT=10612 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `busan_gis_buildings`
--

DROP TABLE IF EXISTS `busan_gis_buildings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `busan_gis_buildings` (
  `A0` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '원천도형ID',
  `A1` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'GIS건물통합식별번호',
  `A2` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '고유번호',
  `A3` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '법정동코드',
  `A4` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '법정동명',
  `A5` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '지번',
  `A6` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '특수지코드',
  `A7` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '특수지구분명',
  `A8` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건축물용도코드',
  `A9` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건축물용도명',
  `A10` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건축물구조코드',
  `A11` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건축물구조명',
  `A12` double DEFAULT NULL COMMENT '건축물면적(㎡)',
  `A13` date DEFAULT NULL COMMENT '사용승인일자',
  `A14` double DEFAULT NULL COMMENT '연면적',
  `A15` double DEFAULT NULL COMMENT '대지면적(㎡)',
  `A16` double DEFAULT NULL COMMENT '높이(m)',
  `A17` double DEFAULT NULL COMMENT '건폐율(%)',
  `A18` double DEFAULT NULL COMMENT '용적율(%)',
  `A19` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건축물ID',
  `A20` tinyint(1) DEFAULT NULL COMMENT '위반건축물여부',
  `A21` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '참조체계연계키',
  `A22` date DEFAULT NULL COMMENT '데이터기준일자',
  `A23` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '원천시도시군구코드',
  `A24` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건물명',
  `A25` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '건물동명',
  `A26` int DEFAULT NULL COMMENT '지상층_수',
  `A27` int DEFAULT NULL COMMENT '지하층_수',
  `A28` timestamp NULL DEFAULT NULL COMMENT '데이터생성변경일자',
  `geom` geometry NOT NULL /*!80003 SRID 5186 */ COMMENT '건물 도형 정보 (원본 좌표계 유지)',
  PRIMARY KEY (`A1`),
  SPATIAL KEY `geom` (`geom`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `busan_street_trees_comprehensive`
--

DROP TABLE IF EXISTS `busan_street_trees_comprehensive`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `busan_street_trees_comprehensive` (
  `id` int NOT NULL AUTO_INCREMENT,
  `loc_nm` varchar(200) DEFAULT NULL,
  `gugun` varchar(50) DEFAULT NULL,
  `sec_timepoint` varchar(100) DEFAULT NULL,
  `sec_endpoint` varchar(100) DEFAULT NULL,
  `plant_distance` double DEFAULT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `total_trees` int DEFAULT NULL,
  `prunus_yedoensis` int DEFAULT NULL,
  `ginkgo` int DEFAULT NULL,
  `sawleaf_zelkova` int DEFAULT NULL,
  `platanus_orientalis` int DEFAULT NULL,
  `platanus` int DEFAULT NULL,
  `chinese_fringe_tree` int DEFAULT NULL,
  `sophora_japonica` int DEFAULT NULL,
  `metasequoia` int DEFAULT NULL,
  `horse_chestnut` int DEFAULT NULL,
  `acer_buergerianum` int DEFAULT NULL,
  `celtis_sinensis` int DEFAULT NULL,
  `tulipifera` int DEFAULT NULL,
  `acer_palmatum` int DEFAULT NULL,
  `firmiana_simplex` int DEFAULT NULL,
  `pin_oak` int DEFAULT NULL,
  `persimmon` int DEFAULT NULL,
  `cornus_kousa` int DEFAULT NULL,
  `chinese_quince` int DEFAULT NULL,
  `goldenrain_tree` int DEFAULT NULL,
  `cinnamon_tree` int DEFAULT NULL,
  `ailanthus_altissima` int DEFAULT NULL,
  `amur_cork_tree` int DEFAULT NULL,
  `babylon_willow` int DEFAULT NULL,
  `three_flowered_maple` int DEFAULT NULL,
  `japanese_elm` int DEFAULT NULL,
  `Jujube` int DEFAULT NULL,
  `silver_magnolia` int DEFAULT NULL,
  `kurogane_holly` int DEFAULT NULL,
  `pinus_thunbergii` int DEFAULT NULL,
  `myrsinaleaf_oak` int DEFAULT NULL,
  `castanopsis_sieboldii` int DEFAULT NULL,
  `cedrus_deodara` int DEFAULT NULL,
  `camphor_tree` int DEFAULT NULL,
  `torulosa` int DEFAULT NULL,
  `neolitsea_sericea` int DEFAULT NULL,
  `taxus_cuspidata` int DEFAULT NULL,
  `sweet_viburnum` int DEFAULT NULL,
  `etc_tree` int DEFAULT NULL,
  `reference_date` date DEFAULT NULL,
  `geom` point NOT NULL /*!80003 SRID 5186 */,
  PRIMARY KEY (`id`),
  SPATIAL KEY `geom` (`geom`)
) ENGINE=InnoDB AUTO_INCREMENT=836 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `busan_temp`
--

DROP TABLE IF EXISTS `busan_temp`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `busan_temp` (
  `WKT` longtext,
  `A0` text,
  `A1` text,
  `A2` text,
  `A3` text,
  `A4` text,
  `A5` text,
  `A6` text,
  `A7` text,
  `A8` text,
  `A9` text,
  `A10` text,
  `A11` text,
  `A12` text,
  `A13` text,
  `A14` text,
  `A15` text,
  `A16` text,
  `A17` text,
  `A18` text,
  `A19` text,
  `A20` text,
  `A21` text,
  `A22` text,
  `A23` text,
  `A24` text,
  `A25` text,
  `A26` text,
  `A27` text,
  `A28` text
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `osm_edges`
--

DROP TABLE IF EXISTS `osm_edges`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `osm_edges` (
  `from_id` bigint NOT NULL,
  `to_id` bigint NOT NULL,
  `distance` double DEFAULT NULL,
  PRIMARY KEY (`from_id`,`to_id`),
  KEY `idx_osm_edges_from` (`from_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `osm_nodes`
--

DROP TABLE IF EXISTS `osm_nodes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `osm_nodes` (
  `id` bigint NOT NULL,
  `lat` double DEFAULT NULL,
  `lon` double DEFAULT NULL,
  `lat_cell` int DEFAULT NULL,
  `lon_cell` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_osm_nodes_cell` (`lat_cell`,`lon_cell`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-26 21:54:16
