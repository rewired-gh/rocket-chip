// See LICENSE.SiFive for license details.

package freechips.rocketchip.rocket

import chisel3._
import chisel3.util._

object ZBK {
  val SZ_FN     = 5
  def FN_CLMUL  = "b00001".U(SZ_FN.W)
  def FN_CLMULR = "b00010".U(SZ_FN.W)
  def FN_CLMULH = "b00100".U(SZ_FN.W)
  def FN_XPERM8 = "b01000".U(SZ_FN.W)
  def FN_XPERM4 = "b10000".U(SZ_FN.W)
}

class BitManipCryptoInterface(xLen: Int) extends Bundle {
  val fn  = Input(UInt(ZBK.SZ_FN.W))
  val dw  = Input(Bool())
  val rs1 = Input(UInt(xLen.W))
  val rs2 = Input(UInt(xLen.W))
  val rd  = Output(UInt(xLen.W))
}

class BitManipCrypto(xLen: Int) extends Module {
  val io = IO(new BitManipCryptoInterface(xLen))

  // note that it is reversed
  // reuse the 1H
  val isClmul = io.fn(0)
  val out1H = io.fn(4,0)

  // helper
  def asBytes(in: UInt): Vec[UInt] = VecInit(in.asBools.grouped(8).map(VecInit(_).asUInt).toSeq)
  def asNibbles(in: UInt): Vec[UInt] = VecInit(in.asBools.grouped(4).map(VecInit(_).asUInt).toSeq)

  // xperm
  val rs1_bytes = asBytes(io.rs1)
  val rs2_bytes = asBytes(io.rs2)
  val rs1_nibbles = asNibbles(io.rs1)
  val rs2_nibbles = asNibbles(io.rs2)
  val xperm8 = VecInit(rs2_bytes.map(
    x => Mux(x(7,log2Ceil(xLen/8)).orR, 0.U(8.W), rs1_bytes(x)) // return 0 when x overflow
  ).toSeq).asUInt
  val xperm4 = VecInit(rs2_nibbles.map(
    x => if (xLen == 32) Mux(x(3,log2Ceil(xLen/4)).orR, 0.U(4.W), rs1_nibbles(x)) // return 0 when x overflow
    else {
      require(xLen == 64)
      rs1_nibbles(x)
    }
  ).toSeq).asUInt

  // clmul
  val clmul_rs1 = Mux(isClmul, io.rs1, Reverse(io.rs1))
  val clmul_rs2 = Mux(isClmul, io.rs2, Reverse(io.rs2))
  val clmul = clmul_rs2.asBools.zipWithIndex.map({
    case (b, i) => Mux(b, clmul_rs1 << i, 0.U)
  }).reduce(_ ^ _)(xLen-1,0)
  val clmulr = Reverse(clmul)
  val clmulh = Cat(0.U(1.W), clmulr(xLen-1,1))

  // according to FN_xxx above
  io.rd := Mux1H(out1H, Seq(
    clmul, clmulr, clmulh,
    xperm8, xperm4))
}
