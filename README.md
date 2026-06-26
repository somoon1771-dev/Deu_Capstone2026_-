# 보행자 그늘 경로 추천 시스템

## 프로젝트 개요
본 프로젝트는 보행자가 이동할 때 건물 그림자, 날씨, 열 스트레스 정보를 반영하여 더 쾌적한 보행 경로를 추천하는 시스템입니다. OpenStreetMap 기반 보행 네트워크를 활용하여 후보 경로를 생성하고, 건물 폴리곤과 태양 위치 정보를 이용해 경로 구간별 그늘 여부를 분석합니다.

## 주요 기능
- OSM 기반 보행자 경로 탐색
- 다익스트라 기반 후보 경로 생성
- 건물 폴리곤과 태양 위치 기반 그림자 분석
- 온도, 습도, 풍속, 강수 여부를 반영한 쾌적도 계산
- 최단 경로와 쾌적 경로 비교
- CCTV 위치 기반 안전 경로(SAFE) 및 CCTV 마커 표시
- 경로별 shadeScore, weatherComfortScore, heatStressScore, totalScore, safetyScore 제공
- Flutter 모바일 화면에서 검색, 경로 요청, 경로 시각화

## 사용 기술
- Java 21
- Spring Boot
- Maven
- MySQL
- Flutter
- OpenStreetMap
- Tmap / VWorld / 공공데이터 API

## 폴더 구조
```text
src/              Spring Boot 백엔드 소스 코드
mobile/           Flutter 모바일 앱 소스 코드
data/             사용 데이터, 샘플 결과, 데이터 출처 정리
demo/             시연 영상
```

## 실행 방법
1. Java 21과 MySQL을 준비합니다.
2. `src/main/resources/application-example.properties`를 참고하여 로컬에서 `application.properties`를 생성합니다.
3. 필요한 API 키는 환경변수로 설정합니다.
   - `DATA_GO_KR_SERVICE_KEY`
   - `VWORLD_API_KEY`
   - `TMAP_API_KEY`
4. 서버를 실행합니다.

```bash
mvnw.cmd spring-boot:run
```

macOS/Linux 환경에서는 다음 명령어를 사용할 수 있습니다.

```bash
./mvnw spring-boot:run
```

## 제출 자료
- `src/` : 백엔드 소스 코드
- `mobile/` : 모바일 앱 소스 코드
- `data/` : 사용 데이터, 수집 자료 설명, 샘플 결과 JSON, 데이터베이스 스키마
- `demo/demo_video.mp4` : 최종 시연 영상

최종보고서와 발표자료는 수업 제출용 Google Drive 폴더에 별도로 업로드했습니다.

## CCTV 반영 방식
CCTV 데이터는 `busan_general_cctv` 테이블을 사용합니다. 서버는 후보 경로 주변 약 50m 이내의 CCTV를 조회하여 `safetyScore`, `cctvCount`, `nearbyCctvs`를 계산합니다. 이 값은 그늘 점수에 직접 합산되는 방식이 아니라, 별도의 `SAFE` 경로 선택과 CCTV 마커 표시를 위해 사용됩니다.

## 데이터 및 수집 자료
본 프로젝트는 다음 데이터를 활용했습니다.

- OpenStreetMap 기반 보행 네트워크 데이터
- 건물 폴리곤 및 건물 높이 데이터
- 날씨 데이터
- 부산광역시 CCTV 설치 현황정보
- 자체 생성 경로 결과 데이터
- MySQL 데이터베이스 스키마

수집 자료 설명, 샘플 결과 데이터, DB 스키마는 `data/` 폴더에서 확인할 수 있습니다. 대용량 원본 데이터 또는 DB 전체 덤프는 저장소에 직접 포함하지 않고, 데이터 출처와 처리 방식만 문서화했습니다.

## 보안 주의
실제 DB 계정, 비밀번호, API Key, Tailscale 초대 링크, ngrok 토큰 등 민감정보는 포함하지 않습니다.
