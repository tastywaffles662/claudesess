CC ?= cc
CFLAGS ?= -Wall -Wextra -O2

hello: hello.c
	$(CC) $(CFLAGS) -o $@ $<

clean:
	rm -f hello

.PHONY: clean
