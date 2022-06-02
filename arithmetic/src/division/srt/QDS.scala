package division.srt
import chisel3._
import chisel3.util.{BitPat, RegEnable, Valid}
import chisel3.util.experimental.decode._
import utils.extend

class QDSInput(rWidth: Int) extends Bundle {
  val partialReminderCarry: UInt = UInt(rWidth.W)
  val partialReminderSum:   UInt = UInt(rWidth.W)
}

class QDSOutput(ohWidth: Int) extends Bundle {
  val selectedQuotientOH: UInt = UInt(ohWidth.W)
}

class QDS(rWidth: Int, ohWidth: Int, partialDividerWidth: Int) extends Module {
  // IO
  val input = IO(Input(new QDSInput(rWidth)))
  val output = IO(Output(new QDSOutput(ohWidth)))
  val partialDivider = IO(Flipped(Valid(UInt(partialDividerWidth.W))))

  // State, in order to keep divider's value
  val partialDividerReg = RegEnable(partialDivider.bits, partialDivider.valid)
  // for the first cycle: use partialDivider on the IO
  // for the reset of cycles: use partialDividerReg
  // for synthesis: the constraint should be IO -> Output is a multi-cycle design
  //                                         Reg -> Output is single-cycle
  // to avoid glitch, valid should be larger than raise time of partialDividerReg
  val partialDividerLatch = Mux(partialDivider.valid, partialDivider.bits, partialDividerReg)

  // Datapath

  // from P269 in <Digital Arithmetic> : /16， should have got from SRTTable.
  // val qSelTable = Array(
  //   Array(12, 4, -4, -13),
  //   Array(14, 4, -6, -15),
  //   Array(15, 4, -6, -16),
  //   Array(16, 4, -6, -18),
  //   Array(18, 6, -8, -20),
  //   Array(20, 6, -8, -20),
  //   Array(20, 8, -8, -22),
  //   Array(24, 8, -8, -24)/16
  // )
  val columnSelect = partialDividerLatch
  val selectRom: Vec[Vec[UInt]] = VecInit(
    VecInit("b111_0100".U, "b111_1100".U, "b000_0100".U, "b000_1101".U),
    VecInit("b111_0010".U, "b111_1100".U, "b000_0110".U, "b000_1111".U),
    VecInit("b111_0001".U, "b111_1100".U, "b000_0110".U, "b001_0000".U),
    VecInit("b111_0000".U, "b111_1100".U, "b000_0110".U, "b001_0010".U),
    VecInit("b110_1110".U, "b111_1010".U, "b000_1000".U, "b001_0100".U),
    VecInit("b110_1100".U, "b111_1010".U, "b000_1000".U, "b001_0100".U),
    VecInit("b110_1100".U, "b111_1000".U, "b000_1000".U, "b001_0110".U),
    VecInit("b110_1000".U, "b111_1000".U, "b000_1000".U, "b001_1000".U)
  )

  val adderWidth = rWidth + 1
  val yTruncate: UInt = input.partialReminderCarry + input.partialReminderSum
  val mkVec = selectRom(columnSelect)
  val selectPoints = VecInit(mkVec.map { mk =>
    (extend(yTruncate, adderWidth).asUInt
      + extend(mk, adderWidth).asUInt).head(1)
  }).asUInt

  // decoder or findFirstOne here, prefer decoder, the decoder only for srt4
  output.selectedQuotientOH := chisel3.util.experimental.decode.decoder(
    selectPoints,
    TruthTable(
      Seq(
        BitPat("b???0") -> BitPat("b10000"), //2
        BitPat("b??01") -> BitPat("b01000"), //1
        BitPat("b?011") -> BitPat("b00100"), //0
        BitPat("b0111") -> BitPat("b00010") //-1
      ),
      BitPat("b00001") //-2
    )
  )
}
