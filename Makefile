.PHONY: negative-test

negative-test:
	@GREEN="$$(printf '\033[32m')"; \
	RED="$$(printf '\033[31m')"; \
	CYAN="$$(printf '\033[36m')"; \
	MAGENTA="$$(printf '\033[35m')"; \
	RESET="$$(printf '\033[0m')"; \
	ant -f extend/CostingEngine/build.xml run-negative-inventory-test 2>&1 | sed \
		-e "s/BUILD SUCCESSFUL/$${GREEN}&$${RESET}/" \
		-e "s/BUILD FAILED/$${RED}&$${RESET}/" \
		-e "s/Exception/$${RED}&$${RESET}/" \
		-e "s/\[javac\]/$${CYAN}&$${RESET}/" \
		-e "s/\[java\]/$${MAGENTA}&$${RESET}/"
