#Write a program that prints FizzBuzz

.data
fizztext: .asciiz "fizz\n"
buzztext: .asciiz "Buzz\n"
fizzbuzztext: .ascii "fizzbuzz\n"

.text
.globl main

main: 
li $t0, 1

loop:
bgt $t0,100, Exit

#check if divisible by 15
rem $t1, $t0, 15
beq $t1, $zero, FizzBuzz

#check if divisible by 3
rem $t1, $t0, 3
beq $t1, $zero, Fizz

#check if divisible by 5
rem $t1, $t0, 5
beq $t1, $zero, Buzz

#print the number
li $v0, 1
move $a0, $t0
syscall
j NewLine

FizzBuzz:
li $v0, 4
la $a0, fizzbuzztext
syscall
j NextNumber

Fizz:
li $v0, 4
la $a0, fizztext
syscall
j NextNumber

Buzz:
li $v0, 4
la $a0, buzztext
syscall
j NextNumber

NewLine:
li $v0, 11
li $a0, '\n'
syscall

NextNumber:
addi $t0, $t0, 1
j loop

Exit:
li $v0, 10
syscall


