import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.lib._

case class LineCut(Iw : Int, Hout : Int, width : Int) extends Component{
  val io = new Bundle{
    val lineIn = in Vec(SInt(width bits), Hout)
    val mtxOut = out Vec(SInt(width bits), Iw )
    val valid = in Bool()
    val outValid = out  Bool()
    val outReady = in Bool()
  }

  implicit class list2vec[T <: Data](list: List[T]) {def toVec = (Vec(list))}

  val IwNumber = scala.math.ceil(Hout/Iw).toInt
  val regGroup = List.tabulate(IwNumber, Iw)((i, j) => Reg(SInt(width bits)) init(0)).map(_.toVec).toVec

  when(io.valid){
    val vldRegGroupConnect = List.tabulate(IwNumber, Iw)((i, j) => regGroup(i)(j) := io.lineIn(j + Iw*i))
  }.elsewhen(io.outReady){
    val NoVldRegGroupConnect = List.tabulate(IwNumber-1, Iw)((i, j) => regGroup(i)(j) := regGroup(i+1)(j))
  }

  val mtxOutConnect = List.tabulate(Iw)(i => io.mtxOut(i) := regGroup(0)(i))

  val validDelay = Delay(that = io.valid, cycleCount = IwNumber, init = False, when = io.outReady)

  val regOutValid = Reg(Bool()) init (False)
  when(io.valid){
    regOutValid := True
  }.elsewhen(validDelay){
    regOutValid := False
  }
  io.outValid := regOutValid

}

case class Line2Mtrix(Iw : Int, Ww : Int, Hin : Int, Hout : Int, width : Int, K : Int, S : Int, Channel : Int, Bi : Int) extends Component{
  val fmOutType = HardType(Vec(Vec(SInt(width bits), Iw), Ww))

  val io = new Bundle{
    val fmOut = master Stream(fmOutType)
    val lineData = slave Stream(Vec(SInt(width bits), Hin))
    val row = out UInt(log2Up(K) bits)
    val channel = out UInt(log2Up(Channel) bits)
    val peSwitch = in Bool()
    val ramSwitch = out Bool()
  }

  //val FifoDataType = HardType(StreamFifo(dataType = Vec(SInt(width bits), Iw), depth = IwNumber))
  implicit class list2vec[T <: Data](list: List[T]) {def toVec = (Vec(list))}

  val IwNumber = scala.math.ceil(Hout/Iw).toInt

  val ReadKCnt = Counter(0 until K)
  val ReadRowCnt = Counter(0 until K)
  val ReadChannelCnt = Counter(0 until Channel)
  val LineRegGroup = List.tabulate(K, Hout)((i, j) => Reg(SInt(width bits)) init(0)).map(_.toVec).toVec
  val FifoPopVld = Vec(Bool(), Ww)
  val FifoPushRdy = Vec(Bool(), Ww)
  val FifoGroup = List.tabulate(Ww){i => new StreamFifo(dataType = Vec(SInt(width bits), Iw), depth = IwNumber)}
  val FifoWrCnt = Counter(0 until(Ww))
  val LineCutInst = new LineCut(Iw, Hout, width)
  val OutFlag = Reg(Bool()) init(False)
  val FifoVldConnect = List.tabulate(Ww){i =>
    FifoPopVld(i) := FifoGroup(i).io.pop.valid
    FifoPushRdy(i) := ~FifoGroup(i).io.push.ready
    FifoGroup(i).io.pop.ready := io.fmOut.ready & OutFlag
    io.fmOut.payload(i) := FifoGroup(i).io.pop.payload
  }

  when(FifoPushRdy.andR){
    OutFlag := True
  }.elsewhen(!FifoPopVld.andR){
    OutFlag := False
  }
//  OutFlag := FifoPushRdy.andR

  when(io.peSwitch){
    ReadKCnt.clear()
    ReadRowCnt.clear()
    ReadChannelCnt.clear()
    FifoWrCnt.clear()
  }
  io.ramSwitch := io.peSwitch

  //output logic
  io.fmOut.valid := FifoPopVld.andR

  //read data from the interface
  when(io.lineData.fire){
    for(i <- 0 until K; j <- 0 until(Hout)){
      LineRegGroup(i)(j) := io.lineData.payload(i + j*S)
    }
  }

  //fill the Output FIFO
  val LinecutOutOver = Bool()
  LinecutOutOver := !LineCutInst.io.outValid & Delay(that = LineCutInst.io.outValid, cycleCount = 1, init = False)
  when(LinecutOutOver){
    FifoWrCnt.increment()
  }
  val SelectFlag = Vec(Reg(Bool()) init(False), Ww)
  LineCutInst.io.outReady.noCombLoopCheck
  switch (FifoWrCnt.value){
    for(i <- 0 until(Ww)){
      is(i){
        for(j <- 0 until(Ww)){
          if(i == j){
            SelectFlag(j) := True
          }else{
            SelectFlag(j) := False
          }
        }
        LineCutInst.io.outReady := FifoGroup(i).io.push.ready
      }
    }
  }

  val fifoGroupConnect = List.tabulate(Ww){(t) =>
    FifoGroup(t).io.push.payload := LineCutInst.io.mtxOut
    FifoGroup(t).io.push.valid := LineCutInst.io.outValid & SelectFlag(t)
//    LineCutInst.io.outReady := FifoGroup(t).io.push.ready
  }

//the counter ctrl the module start
  val StartReg = Reg(Bool()) init(False)
  when(io.lineData.fire){StartReg := True}

  //fill the Bi module
  when(LinecutOutOver  || !StartReg){
//    switch(ReadKCnt.value){
//      for(i <- 0 until(K)){
//        is(i){
//          LineCutInst.io.lineIn := LineRegGroup(i).toVec
//          LineCutInst.io.valid := True
//        }
//      }
//    }
    LineCutInst.io.lineIn := LineRegGroup(ReadKCnt.value)
    LineCutInst.io.valid := True
    ReadKCnt.increment()
  }.otherwise{
    LineCutInst.io.valid := False
    LineCutInst.io.lineIn := LineRegGroup(0)
  }


  val ReadKCntOverFlowNeg = Bool()
  ReadKCntOverFlowNeg := !ReadKCnt.willOverflowIfInc & Delay(that = ReadKCnt.willOverflowIfInc, cycleCount = 1, init = False)
  when(ReadKCntOverFlowNeg){
    ReadRowCnt.increment()
  }
  val ReadRowCntOverFlowNeg = Bool()
  ReadRowCntOverFlowNeg := !ReadRowCnt.willOverflowIfInc &  Delay(that = ReadRowCnt.willOverflowIfInc, cycleCount = 1, init = False)
  when(ReadRowCntOverFlowNeg){
    ReadChannelCnt.increment()
  }

  //ctrl the input interface
  io.row := ReadRowCnt.value
  io.channel := ReadChannelCnt.value
  val lineDataReadyReg = Reg(Bool()) init (False)
  when(ReadKCntOverFlowNeg){
    lineDataReadyReg := True
  }.elsewhen(io.lineData.fire){
    lineDataReadyReg := False
  }
  io.lineData.ready := lineDataReadyReg

}

object Line2Mtrix  {
  def main(args: Array[String]): Unit = {
    val Iw = 5
    val Ww = 2
    val Hin = 10
    val Hout = 8
    val width = 8
    val K = 3
    val S = 1
    val Channel = 10
    val Bi = 1

    val dutConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz))

    SimConfig.withWave.withConfig(dutConfig).compile(new Line2Mtrix(Iw , Ww , Hin , Hout , width , K , S , Channel , Bi ) {
      io.simPublic()
    }).doSim { dut =>

      import dut.{clockDomain, io}
      clockDomain.forkStimulus(10)

      io.fmOut.ready #= true
      io.lineData.valid #= true
      io.peSwitch #= false


      var testData = 1
      for(a <- 0 until(1000)){
        if(io.lineData.ready.toBoolean){
          testData = (testData + 1)%100
        }
        io.lineData.payload.foreach(t => t #= testData + t.toInt%10)
        clockDomain.waitSampling()
      }

    }
  }
}

