.PHONY: verify run demo

verify:
	./mvnw --batch-mode --no-transfer-progress verify

run:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=local-demo

demo: verify
	node scripts/demo.mjs
