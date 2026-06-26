# Submission Checklist

## GitHub 업로드 확인

- [ ] 저장소가 Public 상태인지 확인
- [ ] ZIP 파일 하나만 올리지 않고 압축을 푼 폴더/파일 구조가 보이게 업로드
- [ ] `src/` 백엔드 소스 코드 확인
- [ ] `mobile/` 모바일 앱 소스 코드 확인
- [ ] `data/` 사용 데이터 출처, DB 스키마, 샘플 결과 확인
- [ ] `demo/` 시연 영상 또는 Release 안내 확인
- [ ] `README.md` 메인 화면 표시 확인

## 데이터 자료 확인

- [ ] `data/sources/README.md`에 데이터 출처 정리
- [ ] `data/sources/SOURCE_URLS.txt`에 원본 URL 정리
- [ ] VWorld GIS건물 데이터 출처 포함
- [ ] 부산광역시 CCTV 설치 현황정보 출처 포함
- [ ] 부산광역시 구군 가로수 현황 출처 포함
- [ ] `data/database/capstone_schema.sql` DB 스키마 포함
- [ ] `data/results/` 경로 결과 샘플 포함

## 보안 확인

- [ ] `application.properties` 실제 운영 설정 파일 없음
- [ ] DB 비밀번호 없음
- [ ] API Key 없음
- [ ] Tailscale 초대 링크 없음
- [ ] ngrok 토큰 없음
- [ ] `mobile/android/local.properties` 없음
- [ ] `.dart_tool`, `.gradle`, `target` 등 빌드/캐시 파일 없음

## Google Drive 별도 제출

- [ ] 최종보고서 업로드
- [ ] 발표자료 PPT/PDF 업로드
- [ ] 기타 문서 업로드
- [ ] 링크 권한 확인
