# Data

본 프로젝트에서 사용한 데이터, DB 스키마, 수집 자료 출처, 샘플 결과 데이터를 정리한 폴더입니다.

## 사용 데이터

### 1. OpenStreetMap 보행 네트워크 데이터
- 보행자 경로 탐색을 위한 도로/보행 네트워크 데이터로 사용했습니다.
- 노드와 엣지를 기반으로 후보 경로를 생성하고, 다익스트라 알고리즘 기반 경로 탐색에 활용했습니다.
- 관련 테이블: `osm_nodes`, `osm_edges`

### 2. VWorld GIS건물일반집합정보 / GIS건물공간정보
- 출처: VWorld 디지털트윈국토 공간정보 다운로드
- URL: https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=5
- 건물의 위치, 형태, 높이 정보를 기반으로 그림자 분석에 사용했습니다.
- 관련 테이블: `busan_gis_buildings`, `building_features`, `building_tiles`, `building_tile_features`

### 3. 부산광역시 CCTV 설치 현황정보
- 출처: 공공데이터포털
- URL: https://www.data.go.kr/data/15120867/openapi.do
- 부산 지역 CCTV 위치 데이터를 수집/저장하고, 경로 주변 CCTV 접근성 계산 및 SAFE 경로/마커 표시 자료로 활용했습니다.
- 관련 테이블: `busan_general_cctv`

### 4. 부산광역시 구군 가로수 현황
- 출처: 공공데이터포털
- URL: https://www.data.go.kr/data/15040363/openapi.do
- 부산 지역 가로수 현황 데이터를 수집/저장하여 보행 환경 및 그늘 관련 데이터로 활용할 수 있도록 구성했습니다.
- 관련 테이블: `busan_street_trees_comprehensive`

### 5. 기상 데이터
- 온도, 습도, 풍속, 풍향, 강수 여부 등을 경로 쾌적도 계산에 반영했습니다.
- 관련 테이블: `busan_temp`

### 6. 경로 결과 데이터
- `results/route_result.json`
- `results/route-check-response.json`

위 파일은 경로 탐색 및 점수 계산 결과를 확인하기 위한 샘플 결과 데이터입니다.

## 데이터베이스 스키마

- `database/capstone_schema.sql`

프로젝트에서 사용한 MySQL 데이터베이스의 테이블 구조를 확인할 수 있는 스키마 파일입니다.
확인된 주요 테이블은 다음과 같습니다.

```text
- building_features
- building_tile_features
- building_tiles
- busan_general_cctv
- busan_gis_buildings
- busan_street_trees_comprehensive
- busan_temp
- osm_edges
- osm_nodes
```

해당 SQL 파일은 현재 확인 기준으로 `CREATE TABLE` 중심의 구조 파일이며, 실제 데이터 행을 삽입하는 `INSERT INTO` 구문은 포함되어 있지 않습니다.

## 폴더 구성

```text
sources/    사용 데이터 출처 및 활용 방식 정리
results/    경로 탐색 결과 샘플 JSON
database/   DB 스키마 SQL
```

## 주의사항

실제 DB 계정, 비밀번호, API Key 등 민감정보는 포함하지 않았습니다.
대용량 원본 데이터 또는 DB 전체 덤프는 저장소에 직접 포함하지 않고, 데이터 출처와 처리 방식, DB 스키마, 샘플 결과 데이터를 함께 제공합니다.
