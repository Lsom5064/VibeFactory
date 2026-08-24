# Native Android Deployment

현재 AWS 배포 자료는 `native/`에 있다.

- `native/bootstrap-native-ec2.sh`
- `native/rsync-to-native-ec2.sh`
- `native/vibefactory-native-server.env.example`
- `native/vibefactory-native-server.service`
- `native/nginx-vibefactory-native-canary.conf`

이전 Flutter 배포 자료와 다운로드 DB는 `../flutter/aws/`로 이동했다.
`vibeFactory.pem`은 Git에 포함하지 않는 공유 SSH 키이므로 이 위치에 유지한다.
