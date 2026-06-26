캡스톤 서버 실행/개선 설명서
============================

1. 프로젝트 개요
----------------
이 서버는 단순 최단거리 길찾기가 아니라, 보행 경로를 세그먼트 단위로 나눈 뒤
각 구간의 그늘 점수, 거리 효율, 날씨 기반 쾌적도 점수를 함께 계산하여
'걷기 좋은 길'을 추천하는 Spring Boot 서버입니다.

핵심 API:
- POST /api/routes : 출발지/도착지 기반 경로 후보 계산
- GET  /health     : 서버 상태 확인


2. 실행 방법
------------
필수 환경:
- JDK 21
- 인터넷 연결: 최초 실행 시 Maven Wrapper가 Maven/라이브러리를 내려받을 수 있어야 함

실행 순서:
1) zip 압축 해제
2) 프로젝트 루트에서 아래 명령 실행

Windows:
  mvnw.cmd spring-boot:run

macOS/Linux:
  chmod +x mvnw
  ./mvnw spring-boot:run

3) 브라우저 또는 API 도구에서 확인
  http://localhost:8080/health


3. 데이터 파일
--------------
OSM PBF 파일 위치:
  data/osm/gyeongsang-non-military.osm.pbf

application.properties 설정:
  capstone.routing.osm.enabled=true
  capstone.routing.osm.pbf-path=data/osm/gyeongsang-non-military.osm.pbf

주의:
- 이 파일이 없으면 OSM 기반 경로 생성 품질이 떨어질 수 있습니다.
- bbox 범위가 너무 넓으면 속도가 느려질 수 있습니다.


4. API 테스트 예시
------------------
POST http://localhost:8080/api/routes
Content-Type: application/json

{
  "startLat": 35.1532,
  "startLon": 129.0594,
  "endLat": 35.1579,
  "endLon": 129.0612,
  "departureTime": "2026-06-01T14:00:00+09:00",
  "preference": "BALANCED",
  "cloudCover": 20,
  "raining": false,
  "temperatureCelsius": 32,
  "humidity": 65,
  "windSpeedMps": 1.8
}

curl 예시:
curl -X POST http://localhost:8080/api/routes ^
  -H "Content-Type: application/json" ^
  -d "{\"startLat\":35.1532,\"startLon\":129.0594,\"endLat\":35.1579,\"endLon\":129.0612,\"departureTime\":\"2026-06-01T14:00:00+09:00\",\"preference\":\"BALANCED\",\"cloudCover\":20,\"raining\":false,\"temperatureCelsius\":32,\"humidity\":65,\"windSpeedMps\":1.8}"

