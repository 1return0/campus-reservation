# CampusReserve project layout

CampusReserve keeps its microservice boundaries, but each business service uses
the directory convention used by `novel-cloud`.

```text
controller/
  front/       Browser/student-facing HTTP endpoints
  admin/       Support-staff or administrator HTTP endpoints
dto/
  req/         HTTP request DTOs
  resp/        HTTP response DTOs
service/       Business-service interfaces
service/impl/  Transactional business implementations
dao/
  entity/      MyBatis-Plus persistence entities
  mapper/      MyBatis-Plus Mapper interfaces and guarded SQL
manager/
  cache/       Cache coordination and invalidation
  mq/          RabbitMQ publisher, consumer, delivery and event contracts
task/          Scheduled jobs
config/        Spring configuration and property binding
security/      JWT authentication filters and security chain
web/           Request correlation and other servlet web filters
domain/        Business state enums and value concepts
```

## Package ownership

- `controller` accepts HTTP only. It validates DTOs and calls `service`.
- `service` defines the business capability required by a controller, consumer
  or task. `service.impl` owns transactions and business decisions.
- `dao` is the only layer that knows persistence entities and mapper SQL.
- `manager.mq` owns broker-facing details. It is not a Controller or Service.
- `task` only triggers scheduled work; it does not own business rules.
- `domain` contains business vocabulary, not HTTP DTOs or database entities.

`campus-common`, `campus-security` and `gateway-service` are shared or edge
modules. They retain their specialised layouts rather than copying every
business-service directory mechanically.
