package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"unsafe"
)

func terminal(fd uintptr, operation uintptr, state *syscall.Termios) error {
	_, _, err := syscall.Syscall(syscall.SYS_IOCTL, fd, operation, uintptr(unsafe.Pointer(state)))
	if err != 0 {
		return err
	}
	return nil
}

func readSecret(prompt string) (string, error) {
	fd := os.Stdin.Fd()
	var previous syscall.Termios
	if err := terminal(fd, syscall.TCGETS, &previous); err != nil {
		return "", errors.New("interactive input requires a terminal; use a private credential file")
	}
	hidden := previous
	hidden.Lflag &^= syscall.ECHO
	if err := terminal(fd, syscall.TCSETS, &hidden); err != nil {
		return "", err
	}
	restore := func() { _ = terminal(fd, syscall.TCSETS, &previous) }
	interrupts := make(chan os.Signal, 1)
	done := make(chan struct{})
	signal.Notify(interrupts, os.Interrupt, syscall.SIGTERM)
	go func() {
		select {
		case <-interrupts:
			restore()
			fmt.Fprintln(os.Stderr, "\nCancelled")
			os.Exit(130)
		case <-done:
		}
	}()
	defer func() { restore(); signal.Stop(interrupts); close(done); fmt.Fprintln(os.Stderr) }()
	fmt.Fprint(os.Stderr, prompt)
	value, err := bufio.NewReader(io.LimitReader(os.Stdin, 16385)).ReadString('\n')
	if err != nil {
		return "", err
	}
	if len(value) > 16384 {
		return "", errors.New("secret input is too long")
	}
	return strings.TrimSpace(value), nil
}
