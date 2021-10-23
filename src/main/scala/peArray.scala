
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

}

case class peArrayCtrl(Iw : Int, Wh : Int, Ww : Int, width : Int, Ni : Int, Hout : Int, Ci : Int, K : Int) extends Component{
  val weightType = HardType(Vec(Vec(SInt(width bits), Ww), Wh))
  val fmInType = HardType(Vec(Vec(SInt(width bits), Iw), Ww))
  val fmOutType = HardType(Vec(Vec(SInt(width bits), Iw), Wh))

  val NiNumber = scala.math.ceil(Ni / Wh).toInt
  val HoutNumber = scala.math.ceil(Hout / Iw).toInt
  val CiKKNumber = scala.math.ceil(Ci*K*K / Ww).toInt

  val io = new Bundle{
    val weightIn = slave Stream(weightType)
    val fmIn = slave Stream(fmInType)
    val fmOut = master Stream(fmOutType)
    val peSwitch = out Bool
  }.noCombLoopCheck
  val peArrayInst = new peArray(Iw, Wh, Ww, width)
  val fifoInst = new StreamFifo(dataType = fmOutType, depth = NiNumber*HoutNumber)

  /*
   @ need to fixme
   */
  val wgtRegGroup = List.tabulate(NiNumber, Wh, Ww)((i, j, k) => Reg(SInt(width bits))).map(_.map(_.toVec).toVec).toVec

  implicit class list2vec[T <: Data](list: List[T]) {def toVec = (Vec(list))}

  val fmRegGroup = List.tabulate(HoutNumber, Ww, Iw)((i, j, k) => Reg(SInt(width bits))).map(_.map(_.toVec).toVec).toVec

  val NiCnt  =Counter(0 until NiNumber)
  val HoutCnt = Counter(0 until  HoutNumber)
  val CiKKCnt = Counter(0 until CiKKNumber)

  // OutFlag indicate the output Matrix is ready
  val OutFlag = Reg(Bool()) init(False)

  val FmDataEnd = Reg(Bool()) init(False)
  val WgtDataEnd = Reg(Bool()) init(False)

  val ReadOver = Bool()
  val ReadNiCnt = Counter(0 until NiNumber)
  val ReadHoutCnt = Counter(0 until HoutNumber)

  // LastValue is the value that read from FIFO
  // PeResult is the PeUnit's output
  val LastValue = List.tabulate(Wh, Iw)((i, j) => Reg(SInt(width bits)))
  val LastValueDelay = List.tabulate(Wh, Iw)((i, j) => Reg(SInt(width bits)))
  val PeResult = List.tabulate(Wh, Iw)((i, j) => Reg(SInt(width bits)))


  /*
   @Sequence alignment
   @need to fixme
   */
  val delayConnect = List.tabulate(Wh, Iw){(i, j) =>
    LastValueDelay(i)(j) := Delay(LastValue(i)(j), log2Up(Ww) - 2)
    fifoInst.io.push.payload(i)(j) := (LastValueDelay(i)(j) + PeResult(i)(j)).resized
    io.fmOut.payload(i)(j) := fifoInst.io.pop.payload(i)(j)
  }

//reset the counter when the fifo is empty, which indicate that the PE is about to start
//  when(!fifoInst.io.pop.valid){
//    NiCnt.clear()
//    HoutCnt.clear()
//    CiKKCnt.clear()
//  }

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
  }.elsewhen(!fifoInst.io.pop.valid){
    OutFlag := False
  }.otherwise{
    OutFlag := OutFlag
  }

//  when(NiCnt === NiNumber && HoutCnt === HoutNumber - 1){
//    ReadOver := False
//  }.elsewhen(ReadNiCnt === NiNumber-1 && ReadHoutCnt === HoutNumber-1){
//    ReadOver := True
//  }.otherwise{
//    ReadOver:= ReadOver
//  }

  when(ReadNiCnt.willOverflow){
    WgtDataEnd := True
  }.elsewhen(NiCnt === NiNumber-1 && HoutCnt === HoutNumber -1){
    WgtDataEnd := False
  }

  when(ReadHoutCnt.willOverflow){
    FmDataEnd := True
  }.elsewhen(NiCnt === NiNumber-1 && HoutCnt === HoutNumber -1){
    FmDataEnd := False
  }

  ReadOver := FmDataEnd & WgtDataEnd

  //read date from the stream interface
  val FmInFire = Bool()
  FmInFire := io.fmIn.fire

  // read fm buffer data
  when(io.fmIn.fire) {
    ReadHoutCnt.increment()
    fmRegGroup(ReadHoutCnt.value).zip(io.fmIn.payload).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }
  }

    peArrayInst.io.fmMat.zip(fmRegGroup(HoutCnt.value)).foreach{
      case (t, t1) => t.zip(t1).foreach{
        case (int, int1) => int := int1
      }
    }


    peArrayInst.io.wgtMat.zip(wgtRegGroup(NiCnt.value)).foreach{
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
 */
  when(io.weightIn.fire){
    ReadNiCnt.increment()
    wgtRegGroup(ReadNiCnt.value).zip(io.weightIn.payload).foreach{case (t, t1) =>
      t.zip(t1).foreach{ case (ints, ints1) => ints := ints1}}
  }


/*
   @ need to fixme
 */
  when((NiCnt === 0) & (!OutFlag) & !WgtDataEnd  ){
    io.weightIn.ready := True
  }.otherwise{
    io.weightIn.ready := False
  }

  when((NiCnt === 0) & (!OutFlag) & !FmDataEnd ){
    io.fmIn.ready := True
  }.otherwise{
    io.fmIn.ready := False
  }

  //compute the data

  io.peSwitch := OutFlag
  io.fmOut.valid := !fifoInst.io.pop.ready


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
    // val temp = List.tabulate(Wh, Iw)((i, j) => LastValueDelay(i)(j) := Delay(LastValue(i)(j), log2Up(Ww) - 1))
    LastValueDelay.zip(LastValue).foreach{ case(rowdelay , row) => rowdelay.zip(row).foreach{
      case(delayvalue , value) => delayvalue := Delay(value , log2Up(Ww) - 1)
      }
    }

  }

  fifoInst.io.pop.ready := !OutFlag && ReadOver && !(CiKKCnt.value === 0)


  /*
    @ need to fixme
   */
  when(Delay(that = (!OutFlag && ReadOver), cycleCount = log2Up(Ww)+1, init = False)){
    fifoInst.io.push.valid := True
  }otherwise{
    fifoInst.io.push.valid := False
  }

  //Data Out Logic
  when(OutFlag){
    io.fmOut.valid := fifoInst.io.pop.valid
    fifoInst.io.pop.ready := io.fmOut.ready
  }otherwise{
    io.fmOut.valid := False
  }


}

object peArrayCtrlSim extends App {
    val Ci = 4
    val Ki = 2
    val Hout = 12
    val Ni = 8
    val Wh = 2
    val Ww = 8
    val Iw = 3
    val width = 8
    val dutConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz))

    SimConfig.withWave.withConfig(dutConfig).compile(new peArrayCtrl(Iw, Wh, Ww, width, Ni, Hout, Ci, Ki) {
      io.fmOut.simPublic()
      io.fmIn.simPublic()
      io.weightIn.simPublic()
    }).doSim { dut =>

      import dut.{clockDomain, io, NiNumber, HoutNumber}
      clockDomain.forkStimulus(10)

      io.fmOut.ready #= true
      io.fmIn.valid #= true
      io.weightIn.valid #= true


      var testData = 1
      for(a <- 0 until(1000)){
        if(io.fmIn.ready.toBoolean){
          testData = (testData + 1)%100
        }
        val FmPayloadConnect = List.tabulate(Ww, Iw)((i, j) => io.fmIn.payload(i)(j) #= testData)
        val WeightPayloadConnect = List.tabulate(Wh, Ww)((i, j) => io.weightIn.payload(i)(j) #= testData)
        clockDomain.waitSampling()
      }

//      clockDomain.waitSampling(1000)

      print("the number of ninumber is: ", NiNumber)
      print("the number of houtnumber is: ", HoutNumber)

    }

}
