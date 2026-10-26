#!/bin/sh
# Builds Cog Maze into out/cogmaze.img, a bootable 1.44 MB floppy image for the RV32I PC.
set -e
cd "$(dirname "$0")"
LLVM=${LLVM:-/opt/homebrew/opt/llvm/bin}
LLD=${LLD:-/opt/homebrew/opt/lld/bin}
mkdir -p out

FLAGS="--target=riscv32-unknown-elf -march=rv32im_zicsr -mabi=ilp32 -mcmodel=medlow -mno-relax -O2 -ffreestanding -nostdlib -fno-pic"
"$LLVM/clang" $FLAGS -c boot.S -o out/boot.o
"$LLVM/clang++" $FLAGS -std=c++20 -fno-exceptions -fno-rtti -fno-threadsafe-statics -fno-asynchronous-unwind-tables \
    -fconstexpr-steps=100000000 -Wall -Wextra -Wno-unused-parameter -c game.cpp -o out/game.o
"$LLD/ld.lld" -T link.ld --gc-sections -o out/cogmaze.elf out/boot.o out/game.o
"$LLVM/llvm-objcopy" -O binary out/cogmaze.elf out/cogmaze.bin
python3 mkfloppy.py out/cogmaze.bin out/cogmaze.img
"$LLVM/llvm-size" out/cogmaze.elf
