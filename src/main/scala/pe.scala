import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.lib._
import scala.util.Random

case class peUnit(ww : Int, width : Int) extends Component{
  val dataType = HardType(SInt(width bits))
  val weightType = HardType(SInt(width bits))
  val retType = HardType(SInt(width bits))

  val io = new Bundle{
    val weight = in Vec(dataType(), ww)
    val fm_data = in Vec(weightType(), ww)
    val result = out (retType())
  }
  val mulRes = io.weight.zip(io.fm_data).map{ case (int, int1) => int * int1}
  val regMulRes = RegNext(Vec(mulRes))

  val n = mulRes.length
  val nUp = 2^log2Up(n)
  val inDataExtended  = Vec(SInt(2*width bits), size = nUp)
  inDataExtended.foreach(_.clearAll())
  inDataExtended.allowOverride
  inDataExtended.zip(mulRes).foreach{ case (int, int1) => int := int1}
  val delay = log2Up(ww) + 1
  io.result := regMulRes.reduceBalancedTree(_ + _ , (s , l) => RegNext(s)).resized

 /*
 def adderTree(inData : Vec[SInt]) : Vec[SInt] = {
    val n = inData.length
    n match {
      case 2 => Vec(inData(0) +^ inData(1))
      case _ =>{
        val (data0, data1) = inData.splitAt(n / 2)
        //adderTree(Vec(data0)).zip(adderTree(Vec(data1))).foreach{ case (int, int1) => int}
        val res1 = adderTree(Vec(data0))
        val res2 = adderTree(Vec(data1))
        RegNext(Vec(res1(0) +^ res2(0)))
      }
    }
  }
  */

  // io.result := adderTree(Vec(regMulRes)).head.resized

}



//object peUnit {
//  def main(args: Array[String]): Unit = {
//    SpinalConfig().generateVerilog(peUnit(8,8))
//  }
//}

// after verifying , the latency is log2Up(Ww) + 1

object peUnit extends App{
  val simWw = 8
  val simSize = 8
  val dutConfig = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz))

  SimConfig.withWave.withConfig(dutConfig).compile(new peUnit(simWw, simSize)).doSim{ dut =>
    import dut.{clockDomain, io, delay}

    clockDomain.forkStimulus(12)

    io.weight.foreach(_ #= 0)
    io.fm_data.foreach(_ #= 0)

    clockDomain.waitSampling(2)

    val inWeight = (0 until simWw).map(_ => Random.nextInt(10) - 5)
    val in_fm_data = (0 until simWw).map(_ => Random.nextInt(10) - 5)

    io.weight.zip(inWeight).foreach{ case (int, i) => int #= i}
    io.fm_data.zip(in_fm_data).foreach{ case (int, i) => int #= i}

    clockDomain.waitSampling(10)

    println(delay)
    println(in_fm_data.zip(inWeight).map{ case (i , j) => i*j}.sum)



  }
}