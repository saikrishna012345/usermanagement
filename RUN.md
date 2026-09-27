# Run Guide

```bash
docker compose up -d
cd discovery-service && mvn spring-boot:run
cd ../user-service && mvn spring-boot:run
cd ../product-service && mvn spring-boot:run
cd ../order-service && mvn spring-boot:run
cd ../gateway-service && mvn spring-boot:run
```

Open Eureka: http://localhost:8761
Gateway: http://localhost:8080
User: http://localhost:8081
Product: http://localhost:8082
Order: http://localhost:8083
