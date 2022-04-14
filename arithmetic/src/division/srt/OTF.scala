package division.srt

import chisel3._
import chisel3.util.{Mux1H}

class OTFInput(qWidth: Int, ohWidth: Int) extends Bundle {
  val quotient = UInt(qWidth.W)
  val quotientMinusOne = UInt(qWidth.W)
  val selectedQuotientOH = UInt(ohWidth.W)
}

class OTFOutput(qWidth: Int) extends Bundle {
  val quotient = UInt(qWidth.W)
  val quotientMinusOne = UInt(qWidth.W)
}

class OTF(radix: Int, qWidth: Int, ohWidth: Int) extends Module {
  val input = IO(Input(new OTFInput(qWidth, ohWidth)))
  val output = IO(Output(new OTFOutput(qWidth)))
  // control

  // datapath
  // q_j+1 in this circle, only for srt4
  val qNext: UInt = Mux1H(
    Seq(
      input.selectedQuotientOH(0) -> "b110".U,
      input.selectedQuotientOH(1) -> "b101".U,
      input.selectedQuotientOH(2) -> "b000".U,
      input.selectedQuotientOH(3) -> "b001".U,
      input.selectedQuotientOH(4) -> "b010".U
    )
  )

  // val cShiftQ:  Bool = qNext >= 0.U
  // val cShiftQM: Bool = qNext <=  0.U
  val cShiftQ:  Bool = input.selectedQuotientOH(ohWidth / 2, 0).orR
  val cShiftQM: Bool = input.selectedQuotientOH(ohWidth - 1, ohWidth / 2).orR

  val qIn:  UInt = (Mux(cShiftQ, qNext, radix.U + qNext))(1, 0)
  val qmIn: UInt = (Mux(!cShiftQM, qNext - 1.U, (radix - 1).U + qNext))(1, 0)

  output.quotient := Mux(cShiftQ, input.quotient, input.quotientMinusOne) ## qIn
  output.quotientMinusOne := Mux(cShiftQM, input.quotientMinusOne, input.quotient) ## qmIn
}