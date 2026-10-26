.text
.globl main

main:
    addiu $t0, $zero, 1
label_400004:
    addiu $at, $zero, 100
    slt $at, $at, $t0
    bne $at, $zero, label_4000a0
    addiu $at, $zero, 15
    div $t0, $at
    mfhi $t1
    beq $t1, $zero, label_400050
    addiu $at, $zero, 3
    div $t0, $at
    mfhi $t1
    beq $t1, $zero, label_400064
    addiu $at, $zero, 5
    div $t0, $at
    mfhi $t1
    beq $t1, $zero, label_400078
    addiu $v0, $zero, 1
    addu $a0, $t0, $zero
    syscall
    j label_40008c
label_400050:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 12
    syscall
    j label_400098
label_400064:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 0
    syscall
    j label_400098
label_400078:
    addiu $v0, $zero, 4
    lui $a0, 4097
    ori $a0, $a0, 6
    syscall
    j label_400098
label_40008c:
    addiu $v0, $zero, 11
    addiu $a0, $zero, 10
    syscall
label_400098:
    addi $t0, $t0, 1
    j label_400004
label_4000a0:
    addiu $v0, $zero, 10
    syscall

.data
dataLabel0:
    .asciiz "fizz\n"

dataLabel1:
    .asciiz "Buzz\n"

dataLabel2:
    .ascii "fizzbuzz\n"

