package float

import chisel3._
import chisel3.util._


/**
  * exp is 10bits SInt, MSB is sign
  *
  *
  * */
class RoundingUnit extends Module{
  val input = IO(Input(new Bundle{
    val invalidExc = Bool() // overrides 'infiniteExc' and 'in'
    val infiniteExc = Bool() // overrides 'in' except for 'in.sign'
    val isInf  = Bool()
    val isZero = Bool()
    val isNaN  = Bool()
    val sig = UInt(23.W)
    val exp = SInt(10.W)
    val rBits = UInt(2.W)
    val sign = Bool()
    val roundingMode = UInt(5.W)
  }))
  val output = IO(Output(new Bundle{
    val data = UInt(32.W)
    val exceptionFlags = Output(Bits(5.W))
  }))

  val roundingMode_near_even   = (input.roundingMode === consts.round_near_even)
  val roundingMode_toZero      = (input.roundingMode === consts.round_minMag)
  val roundingMode_min         = (input.roundingMode === consts.round_min)
  val roundingMode_max         = (input.roundingMode === consts.round_max)
  val roundingMode_near_maxMag = (input.roundingMode === consts.round_near_maxMag)


  val common_overflow = Wire(Bool())
  val common_underflow = Wire(Bool())
  val common_inexact  = Wire(Bool())
  val common_subnorm  = Wire(Bool())

  val sigAfterInc = Wire(UInt(23.W))
  val sigIncr = Wire(Bool())
  val expIncr = Wire(Bool())
  val expBiasedAfterInc = Wire(UInt(8.W))

  /** normal case(not subnormal) */

  /** todo opt it with Mux1H? */
  sigIncr := (roundingMode_near_even && input.rBits(1) && input.rBits(0)) ||
    (roundingMode_min &&  input.sign && input.rBits.orR) ||
    (roundingMode_max && !input.sign && input.rBits.orR) ||
    (roundingMode_near_maxMag && input.rBits(1) && input.rBits(0))

  sigAfterInc := input.sig + sigIncr

  /** for sig = all 1 and sigIncr*/
  expIncr := input.sig.andR && sigIncr

  /** todo: opt it*/
  expBiasedAfterInc := ((input.exp + 127.S)(7,0) + expIncr).asUInt

  val sub_sigShift = Wire(UInt(23.W))
  val sub_sigOut,common_subnormSigOut = Wire(UInt(23.W))
  val sub_expInc = Wire(UInt(8.W))

  // control logic
  // set to 126 according to softfloat
  // todo: merge it with normal case
  val exp_BiasForSub = (input.exp + 126.S(10.W))
  val subnormDist = -exp_BiasForSub
  // todo why we have this case? IN IEEE754 or definded by Hardfloat?
  val common_totalUnderflow = subnormDist > 235.S
  common_subnorm := exp_BiasForSub(9)

  val sub_sigBefore:UInt = Cat(1.U(1.W), input.sig)
  sub_sigShift := (sub_sigBefore >> subnormDist.asUInt)(22,0)
  // todo opt it, creat method for it, it;s jamm32
  val distlagerThan24 = subnormDist.asUInt > 24.U
  val sub_Stickybits = Mux(distlagerThan24, 1.U, (sub_sigBefore << 24 >> subnormDist.asUInt)(22,0).orR || input.rBits.orR)
  val sub_GuardBit = (sub_sigBefore << 24 >> subnormDist.asUInt)(23)
  val sub_rbits : UInt= Cat(sub_GuardBit,sub_Stickybits)

  // todo merge it with sigIncr
  val sub_sigIncr : Bool= (roundingMode_near_even && (sub_rbits.andR || (sub_sigShift(0) && sub_rbits==="b10".U))) ||
    (roundingMode_min && input.sign && sub_rbits.orR) ||
    (roundingMode_max && !input.sign && sub_rbits.orR) ||
    (roundingMode_near_maxMag && sub_rbits(1))

  sub_sigOut := sub_sigShift + sub_sigIncr
  sub_expInc := sub_sigShift.andR && sub_sigIncr
  common_subnormSigOut := Mux(common_totalUnderflow, 0.U ,sub_sigOut )

  dontTouch(exp_BiasForSub)
  dontTouch(subnormDist)
  dontTouch(common_subnorm)
  dontTouch(common_subnormSigOut)
  dontTouch(sigAfterInc)
  dontTouch(common_totalUnderflow)
  dontTouch(sub_sigOut)
  dontTouch(sub_expInc)
  dontTouch(sub_rbits)

  // Exceptions
  val isNaNOut = input.invalidExc || input.isNaN
  val notNaN_isSpecialInfOut = (input.infiniteExc || input.isInf) && (!input.invalidExc) && (!input.isNaN)
  val notNaN_isZero = input.isZero && !isNaNOut
  val commonCase = !isNaNOut && !notNaN_isSpecialInfOut && !input.isZero

  val overflow = commonCase && common_overflow
  val underflow = commonCase && (common_underflow && sub_rbits.orR)
  val inexact = overflow || (commonCase && common_inexact)

  val isZero = input.isZero && underflow

  val overflowSele = roundingMode_min ## roundingMode_max ## roundingMode_toZero ## (roundingMode_near_even || roundingMode_near_maxMag)

  val common_infiniteOut = Mux1H(
    Seq(
      overflowSele(0) -> Cat(input.sign, "h7F800000".U(31.W)),
      overflowSele(1) -> Cat(input.sign, "h7F7FFFFF".U(31.W)),
      overflowSele(2) -> Mux(input.sign, "hFF7FFFFF".U(32.W), "h7F800000".U(32.W)),
      overflowSele(3) -> Mux(input.sign, "hFF800000".U(32.W), "h7F7FFFFF".U(32.W)),
    )
  )

  // exception data with Spike
  val quietNaN = "h7FC00000".U

  val infiniteOut = Cat(input.sign, "h7F800000".U)
  val zeroOut = Cat(input.sign, 0.U(31.W))
  val outSele1H = commonCase ## notNaN_isSpecialInfOut ## isNaNOut ## notNaN_isZero

  /** @todo opt it using hardfloat methods */
  common_overflow := input.exp > 127.S
  common_underflow := common_subnorm
  common_inexact := input.rBits.orR || (common_underflow && sub_rbits.orR)

  val common_sigOut = sigAfterInc
  val common_expOut = expBiasedAfterInc
  dontTouch(common_expOut)
  dontTouch(common_underflow)
  dontTouch(common_overflow)
  dontTouch(overflowSele)
  dontTouch(common_infiniteOut)


  val common_out = Mux(common_overflow, common_infiniteOut,
    Mux(common_subnorm, input.sign ## sub_expInc ## common_subnormSigOut,
      input.sign ## common_expOut ## common_sigOut))

  dontTouch(common_out)

  output.data := Mux1H(Seq(
    outSele1H(0) -> zeroOut,
    outSele1H(1) -> quietNaN,
    outSele1H(2) -> infiniteOut,
    outSele1H(3) -> common_out)
  )
  dontTouch(outSele1H)


  output.exceptionFlags := input.invalidExc ## input.infiniteExc ## overflow ## underflow ## inexact

}

object RoundingUnit {
  def apply(sign: Bool, exp: SInt, sig: UInt, rbits: UInt, rmode: UInt, invalidExc: Bool, infiniteExc: Bool, isNaN: Bool, isInf: Bool, isZero: Bool): Vec[UInt] = {

    val rounder = Module(new RoundingUnit)
    rounder.input.sign := sign
    rounder.input.sig := sig
    rounder.input.exp := exp
    rounder.input.rBits := rbits
    rounder.input.roundingMode := rmode
    rounder.input.invalidExc := invalidExc
    rounder.input.infiniteExc := infiniteExc
    rounder.input.isInf := isInf
    rounder.input.isZero := isZero
    rounder.input.isNaN := isNaN
    VecInit(rounder.output.data, rounder.output.exceptionFlags)
  }

}


