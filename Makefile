.PHONY: verify run demo postgres-demo

verify:
	./mvnw --batch-mode --no-transfer-progress verify

run:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=local-demo

demo: verify
	node scripts/demo.mjs

postgres-demo: verify
	@grep 'Tests run:' target/failsafe-reports/*.txt
