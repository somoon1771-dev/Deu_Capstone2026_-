# 데이터 출처 정리

본 프로젝트에서 사용한 주요 데이터 출처와 활용 목적입니다.

## 1. OpenStreetMap
- 사용 목적: 보행자 경로 탐색을 위한 도로/보행 네트워크 데이터로 사용했습니다.
- 활용 방식: 노드와 엣지를 기반으로 후보 경로를 생성하고, 다익스트라 알고리즘 기반 경로 탐색 및 경로 거리 계산에 활용했습니다.
- 저장/가공 결과: `osm_nodes`, `osm_edges` 테이블 구조는 `data/database/capstone_schema.sql`에서 확인할 수 있습니다.

## 2. VWorld GIS건물일반집합정보 / GIS건물공간정보
- 출처: VWorld 디지털트윈국토 공간정보 다운로드
- URL: https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=5
- 사용 목적: 건물 폴리곤 및 건물 높이 정보를 그림자 분석에 사용했습니다.
- 활용 방식: 건물의 위치, 형태, 높이 정보를 기반으로 태양 위치에 따른 경로 구간별 그림자 여부를 계산했습니다.
- 관련 테이블: `busan_gis_buildings`, `building_features`, `building_tiles`, `building_tile_features`

## 3. 부산광역시 CCTV 설치 현황정보
- 출처: 공공데이터포털
- URL: https://www.data.go.kr/data/15120867/openapi.do
- 사용 목적: 부산 지역 CCTV 위치 데이터를 수집/저장하여 보행 환경 데이터로 활용할 수 있도록 구성했습니다.
- 관련 테이블: `busan_general_cctv`

## 4. 부산광역시 구군 가로수 현황
- 출처: 공공데이터포털
- URL: https://www.data.go.kr/data/15040363/openapi.do
- 사용 목적: 부산 지역 가로수 현황 데이터를 수집/저장하여 보행 환경 및 그늘 관련 데이터로 활용할 수 있도록 구성했습니다.
- 관련 테이블: `busan_street_trees_comprehensive`

## 5. 기상 데이터
- 사용 목적: 온도, 습도, 풍속, 풍향, 강수 여부를 경로 쾌적도 계산에 사용했습니다.
- 활용 방식: 경로별 weatherComfortScore, heatStressScore 계산에 반영했습니다.
- 관련 테이블: `busan_temp`

## 6. 자체 생성 경로 결과 데이터
- `data/results/route_result.json`
- `data/results/route-check-response.json`

위 결과 파일은 실제 경로 요청에 대한 응답 예시와 점수 계산 결과를 확인하기 위한 샘플 데이터입니다.

## 주의사항

대용량 원본 데이터 및 API 기반 수집 데이터 전체는 저장소에 직접 포함하지 않고, 데이터 출처와 활용 방식, DB 스키마, 샘플 결과 데이터를 함께 제공합니다.
실제 DB 계정, 비밀번호, API Key, 개인 인증 토큰 등 민감정보는 포함하지 않았습니다.
