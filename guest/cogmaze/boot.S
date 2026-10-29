# Cog Maze boot sector and C runtime start.
#
# The ROM loads sector 0 to RAM_BASE (0x80000000) and jumps here with
# a0 = RAM size, a1 = boot device capacity in sectors, a2 = ROM print_string,
# a3 = boot device (0 HDD, 1 floppy). This sector loads the rest of the
# program (sectors 1..N) from the same device, then enters _start.

    .equ FDC,       0x10008000
    .equ HDD,       0x10003000
    .equ COMMAND,   0x00
    .equ STATUS,    0x04
    .equ LBA,       0x08
    .equ COUNT,     0x0C
    .equ DMA_ADDR,  0x10

    .section .boot, "ax"
    .globl _boot
_boot:
    mv   s0, a0                 # RAM size
    mv   s1, a1                 # capacity
    mv   s2, a2                 # print_string
    mv   s3, a3                 # boot device
    la   a0, msg_loading
    jalr s2

    li   s4, HDD                # controller of the boot device
    beqz s3, 1f
    li   s4, FDC
1:
    la   t0, __load_end         # sectors still to load
    la   t1, _boot + 512
    sub  t0, t0, t1
    addi t0, t0, 511
    srli s5, t0, 9
    li   s6, 1                  # next LBA
    mv   s7, t1                 # next DMA address

load_loop:
    beqz s5, loaded
    mv   t2, s5                 # this command: min(remaining, 128)
    li   t3, 128
    ble  t2, t3, 2f
    mv   t2, t3
2:
    sw   s6, LBA(s4)
    sw   t2, COUNT(s4)
    sw   s7, DMA_ADDR(s4)
    li   t3, 1
    sw   t3, COMMAND(s4)        # READ
3:
    lw   t3, STATUS(s4)
    andi t4, t3, 6
    beqz t4, 3b
    li   t4, 6
    sw   t4, STATUS(s4)         # clear DONE and ERROR
    andi t4, t3, 4
    bnez t4, load_failed
    add  s6, s6, t2
    sub  s5, s5, t2
    slli t2, t2, 9
    add  s7, s7, t2
    j    load_loop

load_failed:
    la   a0, msg_failed
    jalr s2
4:  wfi
    j    4b

loaded:
    li   sp, 0x80000000
    add  sp, sp, s0             # stack at the top of RAM
    mv   a0, s0
    mv   a1, s3
    mv   a2, s1
    j    _start

msg_loading: .asciz "Loading Cog Maze...\n"
msg_failed:  .asciz "Disk read error\n"

# ---- runtime, loaded with the rest of the program ----

    .section .text.start, "ax"
    .globl _start
_start:
    mv   s0, a0
    mv   s1, a1
    mv   s2, a2
    la   t0, __bss_start        # zero bss
    la   t1, __bss_end
1:  bgeu t0, t1, 2f
    sw   zero, 0(t0)
    addi t0, t0, 4
    j    1b
2:
    la   s3, __init_array_start # static constructors
    la   s4, __init_array_end
3:  bgeu s3, s4, 4f
    lw   t0, 0(s3)
    jalr t0
    addi s3, s3, 4
    j    3b
4:
    mv   a0, s0                 # main(ram size, boot device, capacity)
    mv   a1, s1
    mv   a2, s2
    call main
    li   t0, 0x00100000         # power off with main's return code
    li   t1, 0x5555
    beqz a0, 5f
    slli a0, a0, 16
    li   t1, 0x3333
    or   t1, t1, a0
5:  sw   t1, 0(t0)
6:  wfi
    j    6b

# The compiler may call these for struct copies and zeroing.
    .text
    .globl memset
memset:
    mv   t0, a0
    add  t1, a0, a2
1:  bgeu t0, t1, 2f
    sb   a1, 0(t0)
    addi t0, t0, 1
    j    1b
2:  ret

    .globl memcpy
memcpy:
    mv   t0, a0
    add  t1, a0, a2
1:  bgeu t0, t1, 2f
    lbu  t2, 0(a1)
    sb   t2, 0(t0)
    addi t0, t0, 1
    addi a1, a1, 1
    j    1b
2:  ret

    .globl memmove
memmove:
    bltu a1, a0, 3f
    j    memcpy
3:  add  t0, a0, a2
    add  a1, a1, a2
1:  bgeu a0, t0, 2f
    addi t0, t0, -1
    addi a1, a1, -1
    lbu  t2, 0(a1)
    sb   t2, 0(t0)
    j    1b
2:  ret
