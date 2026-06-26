# Database Schema

본 폴더는 프로젝트에서 사용한 MySQL 데이터베이스 구조를 정리한 폴더입니다.

## 포함 파일

- `capstone_schema.sql`

## 확인 내용

- MySQL dump 형식의 스키마 파일입니다.
- 포함 테이블 수: 9개
- 포함 테이블: building_features, building_tile_features, building_tiles, busan_general_cctv, busan_gis_buildings, busan_street_trees_comprehensive, busan_temp, osm_edges, osm_nodes
- `INSERT INTO` 구문: 0개

## 주의사항

이 파일은 현재 확인 기준으로 테이블 생성 구조 중심의 SQL이며, 실제 데이터 행을 넣는 `INSERT INTO` 구문은 포함되어 있지 않습니다.
따라서 GitHub에는 데이터베이스 구조 확인용으로 포함하고, 대용량 원본 데이터나 실제 운영 DB 덤프는 별도로 관리하는 것이 좋습니다.

실제 DB 계정, 비밀번호, API Key 등 민감정보는 포함하지 않았습니다.
