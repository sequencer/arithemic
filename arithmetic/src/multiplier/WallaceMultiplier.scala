package multiplier

import addition.prefixadder.PrefixSum
import addition.prefixadder.common.BrentKungSum
import chisel3._
import chisel3.util._
import utils.signExt

class WallaceMultiplier(
  val width:  Int
)(
  radixLog2: Int = 2,
  sumUpAdder: PrefixSum = BrentKungSum,
  // TODO: add additional stage for final adder?
  pipeAt: Seq[Int] = Nil,
  // TODO: making addOneColumn to be configurable to add more CSA to make this circuit more configurable?
) extends Multiplier {

  // TODO: use chisel type here?
  def addOneColumn(col: Seq[Bool]): (Seq[Bool], Seq[Bool], Seq[Bool]) =
    col.size match {
      case 1 => // do nothing
        (col, Seq.empty[Bool], Seq.empty[Bool])
      case 2 =>
        val c22 = addition.csa.c22(VecInit(col)).map(_.asBool).reverse
        (Seq(c22(0)), Seq.empty[Bool], Seq(c22(1)))
      case 3 =>
        val c32 = addition.csa.c32(VecInit(col)).map(_.asBool).reverse
        (Seq(c32(0)), Seq.empty[Bool], Seq(c32(1)))
      case 4 =>
        val c53 = addition.csa.c53(VecInit(col :+ false.B)).map(_.asBool).reverse
        (Seq(c53(0)), Seq(c53(1)), Seq(c53(2)))
      case 5 =>
        val c53 = addition.csa.c53(VecInit(col)).map(_.asBool).reverse
        (Seq(c53(0)), Seq(c53(1)), Seq(c53(2)))
      case _ =>
        val (s_1, c_1_1, c_1_2) = addOneColumn(col.take(5))
        val (s_2, c_2_1, c_2_2) = addOneColumn(col.drop(5))
        (s_1 ++ s_2, c_1_1 ++ c_2_1, c_1_2 ++ c_2_2)
    }

  def addAll(cols: Array[_ <: Seq[Bool]], depth: Int): (UInt, UInt) = {
    if (cols.map(_.size).max <= 2) {
      val sum = Cat(cols.map(_.head).reverse)
      val carry = Cat(cols.map(col => if (col.length > 1) col(1) else 0.B).reverse)
      (sum, carry)
    } else {
      val columns_next = Array.fill(2 * width)(Seq[Bool]())
      var cout1, cout2 = Seq[Bool]()
      for (i <- cols.indices) {
        val (s, c1, c2) = addOneColumn(cols(i) ++ cout1)
        columns_next(i) = s ++ cout2
        cout1 = c1
        cout2 = c2
      }

      val needReg = stages.contains(depth)
      val toNextLayer =
        if (needReg)
          // TODO: use 'RegEnable' instead
          columns_next.map(_.map(x => RegNext(x)))
        else
          columns_next

      addAll(toNextLayer, depth + 1)
    }
  }

  // produce Seq(b, 2 * b, ..., 2^digits * b), output width = width + radixLog2 - 1
  val bMultipleWidth = (width + radixLog2 - 1).W
  def prepareBMultiples(digits: Int): Seq[SInt] = {
    if (digits == 0) {
      Seq(signExt(b, bMultipleWidth.get).asSInt)
    } else {
      val lowerMultiples = prepareBMultiples(digits - 1)
      val bPower2 = signExt(b << (digits - 1), bMultipleWidth.get)
      val higherMultiples = lowerMultiples.dropRight(1).map { m =>
        addition.prefixadder.apply(sumUpAdder)(bPower2.asUInt, m.asUInt)(bMultipleWidth.get - 1, 0)
      } :+ (bPower2 << 1)(bMultipleWidth.get - 1, 0)
      lowerMultiples ++ higherMultiples.map(_.asSInt)
    }
  }

  val stage:  Int = pipeAt.size
  val stages: Seq[Int] = pipeAt.sorted

  val bMultiples = prepareBMultiples(radixLog2 - 1)
  val encodedWidth = (radixLog2 + 1).W
  val partialProductLookupTable: Seq[(UInt, SInt)] = Range(-(1 << (radixLog2 - 1)), (1 << (radixLog2 - 1)) + 1).map{
    case 0 =>
      0.U(encodedWidth) -> 0.S(bMultipleWidth)
    case i if i > 0 =>
      i.U(encodedWidth) -> bMultiples(i - 1)
    case i if i < 0 =>
      i.S(encodedWidth).asUInt -> (~bMultiples(-i - 1)).asSInt
  }

  def makePartialProducts(i: Int, recoded: SInt): Seq[(Int, Bool)] = {  // Seq[(weight, value)]
    val bb: UInt = MuxLookup(recoded.asUInt, 0.S(bMultipleWidth), partialProductLookupTable).asUInt
    val s = bb(bMultipleWidth.get - 1)
    val shouldPlus1 = recoded(recoded.getWidth - 1)
    val pp = i match {
      case 0 =>
        Cat(~s, Fill(radixLog2, s), bb)
      case i if i >= width - radixLog2 =>
        Cat(~s, bb)
      case _ =>
        val fillWidth = math.min(width - radixLog2, radixLog2 - 1)
        Cat(Fill(fillWidth, 1.B), ~s, bb)
    }
    printf(s"$i: %b, %b, ${" " * (2 * width - i - pp.getWidth)}%b${" " * i}, %b\n", recoded, bb, pp, s)
    Seq.tabulate(pp.getWidth) {j => (i + j, pp(j)) } :+ (i, shouldPlus1)
  }

  val columns_map = Booth.recode(width)(radixLog2)(a.asUInt)
    .zipWithIndex
    .flatMap{case (x, i) => makePartialProducts(radixLog2 * i, x)}
    .groupBy{_._1}

  val columns = Array.tabulate(2 * width) {i => columns_map(i).map(_._2)}

  val (sum, carry) = addAll(cols = columns, depth = 0)
  z := addition.prefixadder.apply(sumUpAdder)(sum, carry)(2 * width - 1, 0).asSInt
}