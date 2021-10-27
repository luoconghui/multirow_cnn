
import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.lib._

case class peArray(Iw : Int, Wh : Int, Ww : Int, width : Int) extends Component{
  val io = new Bundle {  // Ww == Ih
    val fmMat = in Vec(Vec(SInt(width bits), Iw), Ww)
    val wgtMat = in Vec(Vec(SInt(width bits), Ww), Wh)
    val outMtx = out Vec(Vec(SInt(width bits), Iw), Wh)
  }

  val fmMatReshape = Vec(Vec(SInt(width bits), Ww), Iw)

  //reshape the fmMat

  fmMatReshape := Vec(io.fmMat.transpose.map(Vec(_)))
  /*
  for(i <- 0 until(Iw); j <- 0 until(Ww)){
    fmMatReshape(i)(j) := io.fmMat(j)(i)
  }
   */

  val peMtx = List.tabulate(Wh, Iw)((i,j) => new peUnit(Ww, width))
  for(i <- 0 until(Wh); j <- 0 until(Iw)){
    peMtx(i)(j).io.fm_data := fmMatReshape(j)
    peMtx(i)(j).io.weight := io.wgtMat(i)
    io.outMtx(i)(j) := peMtx(i)(j).io.result
  }
  val peArrayDelay = peMtx(0)(0).delay
}

case class peArrayCtrl(Iw : Int, Wh : Int, Ww : Int, width : Int, Ni : Int, Hout : Int, Ci : Int, K : Int) extends Component{
  val weightType = HardType(Vec(Vec(SInt(width bits), Ww), Wh))
  val fmInType = HardType(Vec(Vec(SInt(width bits), Iw), Ww))
  val fmOutType = HardType(Vec(Vec(SInt(width bits), Iw), Wh))

  val NiNumber = scala.math.ceil(Ni / Wh).toInt
  val HoutNumber = scala.math.ceil(Hout / Iw).toInt
  val CiKKNumber = scala.math.ceil(Ci*K*K / Ww).toInt

  val weightGroupSize = HoutNumber - 1
  val fmGroupSize = HoutNumber

  val io = new Bundle{
    val weightIn = slave Stream(weightType)
    val fmIn = slave Stream(fmInType)
    val fmOut = master Stream(fmOutType)
    val peSwitch = out Bool
  }.noCombLoopCheck
  val peArrayInst = new peArray(Iw, Wh, Ww, width)
  val fifoInst = new StreamFifo(dataType = fmOutType, depth = NiNumber*HoutNumber)


  // define the Reg array for read weight and fm data
  val wgtRegGroup = List.tabulate(weightGroupSize, Wh, Ww)((i, j, k) => Reg(SInt(width bits))).map(_.map(_.toVec).toVec).toVec

  implicit class list2vec[T <: Data](list: List[T]) {def toVec = (Vec(list))}

  val fmRegGroup = List.tabulate(fmGroupSize, Ww, Iw)((i, j, k) => Reg(SInt(width bits))).map(_.map(_.toVec).toVec).toVec

  // indicate the computer process
  val NiCnt  =Counter(0 until NiNumber)
  val HoutCnt = Counter(0 until  HoutNumber)
  val CiKKCnt = Counter(0 until CiKKNumber)

  // OutFlag indicate the output Matrix is ready
  val OutFlag = Reg(Bool()) init(False)

  val FmDataEnd = Reg(Bool()) init(False)
  val WgtDataEnd = Reg(Bool()) init(False)

  val ReadOver = Bool()
  val ReadNiCnt = Counter(0 until weightGroupSize)
  val ReadHoutCnt = Counter(0 until fmGroupSize)

  // LastValue is the value that read from FIFO
  // PeResult is the PeUnit's output
  val LastValue = List.tabulate(Wh, Iw)((i, j) => Reg(SInt(width bits)))
  val LastValueDelay = List.tabulate(Wh, Iw)((i, j) => SInt(width bits))
  val PeResult = List.tabulate(Wh, Iw)((i, j) => Reg(SInt(width bits)))
  val PeResultDelay = List.tabulate(Wh , Iw)((i , j) => SInt(width bits))


  /*
   Sequence alignment
   @need to fixme
   fixmed
   */

  // the fifo read delay is 1 cycle

  /*
  val delayNumber = peArrayInst.peArrayDelay match {
    case 1  => 1
    case _ => peArrayInst.peArrayDelay
  }



  val delayObject = peArrayInst.peArrayDelay match {
    case 1 => PeResult  // also can be LastValue
    case _ => LastValue
  }

  val objectDelay = peArrayInst.peArrayDelay match {
    case 1 => PeResultDelay
    case _ => LastValueDelay
  }

  val noDelayObject = peArrayInst.peArrayDelay match {
    case 1 => LastValue
    case _ => PeResult
  }
   */

  val delayConnect = List.tabulate(Wh, Iw){(i, j) =>
    LastValueDelay(i)(j) := Delay(LastValue(i)(j), peArrayInst.peArrayDelay)
    fifoInst.io.push.payload(i)(j) := (LastValueDelay(i)(j) + PeResult(i)(j)).resized
    io.fmOut.payload(i)(j) := fifoInst.io.pop.payload(i)(j)
  }


  // a sub-W Matrix can be discard
  when(HoutCnt.willOverflowIfInc){NiCnt.increment()}
  // complete a sub-computer
  when(NiCnt.willOverflowIfInc & HoutCnt.willOverflowIfInc){CiKKCnt.increment()}

  when(ReadOver & !OutFlag){
    HoutCnt.increment()
  }

  // OutFlag logic
  when(NiCnt === NiNumber-1 && HoutCnt === HoutNumber-1 && CiKKCnt === CiKKNumber-1){
    OutFlag := True
  }.elsewhen(!fifoInst.io.pop.valid){ // when vaild deassert it means fifo is empty
    OutFlag := False
  }.otherwise{
    OutFlag := OutFlag
  }


  // indicate whether the W or I is readover
  when(ReadNiCnt.willOverflow || ReadNiCnt === U(NiNumber - 1) - NiCnt){
    WgtDataEnd := True
  }.elsewhen(NiCnt.valueNext % weightGroupSize === 0 && HoutCnt === HoutNumber -1){
    WgtDataEnd := False
  }

  when(ReadHoutCnt.willOverflow){
    FmDataEnd := True
  }.elsewhen(NiCnt === NiNumber-1 && HoutCnt === HoutNumber -1){
    FmDataEnd := False
  }

  ReadOver := FmDataEnd & WgtDataEnd

  //read data from the stream interface

  // read fm buffer data
  when(io.fmIn.fire) {
    ReadHoutCnt.increment()
    fmRegGroup(ReadHoutCnt.value).zip(io.fmIn.payload).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }
  }

  // read weight buffer data
  when(io.weightIn.fire){
    ReadNiCnt.increment()
    wgtRegGroup(ReadNiCnt.value).zip(io.weightIn.payload).foreach{case (t, t1) =>
      t.zip(t1).foreach{ case (ints, ints1) => ints := ints1}}

  }
  when(NiCnt === NiNumber - 1 && HoutCnt === HoutNumber - 1) {
    ReadNiCnt.clear()
  }

    peArrayInst.io.fmMat.zip(fmRegGroup(HoutCnt.value)).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }



    peArrayInst.io.wgtMat.zip(wgtRegGroup((NiCnt.value % weightGroupSize).resized)).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }

    PeResult.zip(peArrayInst.io.outMtx).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }

/*
   @ need to fixme
   fixmed
 */
  when((NiCnt % weightGroupSize === 0) & (!OutFlag) & !WgtDataEnd ){
    io.weightIn.ready := True
  }.otherwise{
    io.weightIn.ready := False
  }

  when((NiCnt === 0) & (!OutFlag) & !FmDataEnd ){
    io.fmIn.ready := True
  }.otherwise{
    io.fmIn.ready := False
  }




  when(!OutFlag && ReadOver){
    when(CiKKCnt.value === 0){
      LastValue.foreach(_.foreach(_:= 0))
    }otherwise{
      when(fifoInst.io.pop.fire){LastValue.zip(fifoInst.io.pop.payload).foreach{
        case (ints, ints1) => ints.zip(ints1).foreach{
          case (int, int1) => int := int1
        }
       }
      }
    }
  }




  /*
    @ need to fixme
    fixmed
   */
  val fifoValidDelayNumber = peArrayInst.peArrayDelay match {
    case 1 => 2
    case _ => peArrayInst.peArrayDelay + 1
  }

    when(Delay(!OutFlag && ReadOver, cycleCount = fifoValidDelayNumber, init = False)) {
      fifoInst.io.push.valid := True
    } otherwise {
      fifoInst.io.push.valid := False
    }


  //Data Out Logic
  io.peSwitch := OutFlag

  when(OutFlag){
    io.fmOut.valid := fifoInst.io.pop.valid
    fifoInst.io.pop.ready := io.fmOut.ready
  }otherwise{
    io.fmOut.valid := False
    fifoInst.io.pop.ready := !OutFlag && ReadOver && !(CiKKCnt.value === 0)
  }


}

object peArrayCtrlSim extends App {
    val Ci = 8
    val Ki = 2
    val Hout = 12
    val Ni = 20
    val Wh = 2
    val Ww = 1
    val Iw = 3
    val width = 8
    val dutConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz))

    SimConfig.withWave.withConfig(dutConfig).compile(new peArrayCtrl(Iw, Wh, Ww, width, Ni, Hout, Ci, Ki)).doSim { dut =>

      import dut.{clockDomain, io, NiNumber, HoutNumber , CiKKNumber , fmGroupSize , weightGroupSize ,peArrayInst}
      clockDomain.forkStimulus(1000)

      io.fmOut.ready #= true
      io.fmIn.valid #= true
      io.weightIn.valid #= true

      println("----------------------print informations for debug-----------------------------")



      var testData = 1
      println(s"the number of CiKKnumber is: $CiKKNumber ")
      println(s"the number of Ninumber is: $NiNumber")
      println(s"the number of Houtnumber is: $HoutNumber")
      println(s"the number of fmGroupSize is: $fmGroupSize" )
      println(s"the number of weightGroupSize is: $weightGroupSize")

      for(a <- 0 until 10000){
        if(io.weightIn.ready.toBoolean){
          import scala.util.Random
          testData = Random.nextInt(4) + 1
          // for debug
          val fmArrayForPrint = List.tabulate(Ww , Iw)((i , j) => testData)
          val weightArrayForPrint = List.tabulate(Wh , Ww)((i , j) => testData)
          println("------ this cycle's inputfmMatrix is :")
          fmArrayForPrint.map(_.mkString(",")).foreach(println(_))
          println("------ this cycle's inputweightMatrix is :")
          weightArrayForPrint.map(_.mkString(",")).foreach(println(_))
        }
        else {
          println("------ this cycle's fmIn.ready is False")
        }

        val FmPayloadConnect = List.tabulate(Ww, Iw)((i, j) => io.fmIn.payload(i)(j) #= testData)
        val WeightPayloadConnect = List.tabulate(Wh, Ww)((i, j) => io.weightIn.payload(i)(j) #= testData)

        clockDomain.waitSampling()

        }



//      clockDomain.waitSampling(1000)




      println("-------------------------------------------------------------------------------")
    }

}
