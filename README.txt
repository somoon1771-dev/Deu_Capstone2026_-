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


5. 이번 개선 내용
-----------------
이번 개선본은 기존 코드의 큰 구조는 유지하면서, 보고서/발표에 설명하기 좋은 방식으로
경로 평가 공식을 정리했습니다.

개선 1) 날씨 입력값 추가
RouteRequest에 아래 입력값을 추가했습니다.
- temperatureCelsius : 기온(섭씨)
- humidity           : 습도, 0~1 또는 0~100 모두 입력 가능
- windSpeedMps       : 풍속(m/s)

개선 2) 세그먼트별 날씨 쾌적도 점수 추가
RouteSegmentScore에 아래 응답값을 추가했습니다.
- weatherComfortScore : 해당 구간의 날씨 쾌적도 점수
- heatStressScore     : 더위/비/습도/바람을 반영한 부담 점수

개선 3) 점수 공식 개선
기존에는 그늘 중심 점수가 강했지만, 개선본은 아래 요소를 함께 반영합니다.

segmentScore =
  shadeScore * shadeWeight * 0.58
+ weatherComfortScore * 0.27
+ congestionScore * 0.15

전체 경로 점수는 세그먼트 점수와 거리 점수를 함께 사용합니다.

개선 4) 더운 날에는 그늘 비중 자동 증가
기온/습도 조건 때문에 heatStressScore가 높아지면 BALANCED 모드에서도 그늘 비중이 증가합니다.
즉, 날씨가 덥지 않을 때는 거리 효율을 더 보고, 더운 날에는 그늘 경로를 더 우선합니다.

개선 5) 세그먼트 그래프 최적화 보완
SegmentGraphRouteOptimizer에서 세그먼트 조합 시 역방향 연결도 일부 허용하고,
더운 날 그늘이 부족한 세그먼트에는 추가 비용을 부여하도록 개선했습니다.


6. 발표/보고서용 핵심 설명
--------------------------
기존 길찾기는 최단거리 중심으로 동작하기 때문에 실제 보행자가 느끼는 더위, 햇빛, 습도 같은
환경 요소를 충분히 반영하지 못합니다. 본 프로젝트는 경로를 작은 세그먼트로 분해하고,
각 세그먼트에 대해 그늘 점수와 날씨 기반 쾌적도 점수를 계산합니다. 이후 세그먼트별 점수를
가중합하여 전체 경로를 평가하므로, 단순히 가까운 길이 아니라 실제로 걷기 좋은 길을
추천할 수 있습니다.


7. 다음 개선 추천 순서
---------------------
1) OpenWeatherMap API 연동으로 기온/습도/풍속 자동 입력
2) 건물 높이 데이터 반영
3) 세그먼트별 실제 일사량/그림자 정확도 향상
4) 프론트 지도에서 segmentScore 색상 표시
5) 추천 경로를 SHORTEST / SHADE / BALANCED 3개 모드로 시각화
