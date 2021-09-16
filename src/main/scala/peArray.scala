import spinal.core._
import spinal.sim._
import spinal.core.sim._
import spinal.lib._

case class peArray(Ih : Int, Wh : Int, Ww : Int, width : Int) extends Component{
  val io = new Bundle {
    val fmMat = in Vec(Vec(SInt(width bits), Wh), Ih)
    val wgtMat = in Vec(Vec(SInt(width bits), Wh), Ww)
    val outMtx = out Vec(Vec(SInt(width bits), Ww), Ih)
  }

  val peMtx = List.tabulate(Ih, Ww)((i,j) => new peUnit(Wh, width))
  for(i <- 0 until(Ih); j <- 0 until(Ww)){
    peMtx(i)(j).io.fm_data := io.fmMat(i)
    peMtx(i)(j).io.weight := io.wgtMat(j)
    io.outMtx(i)(j) := peMtx(i)(j).io.result
  }

}

case class peArrayCtrl(Ih : Int, Wh : Int, Ww : Int, width : Int, Ni : Int, Hout : Int) extends Component{
  val weightType = HardType(Vec(Vec(SInt(width bits), Wh), Ww))
  val fmInType = HardType(Vec(Vec(SInt(width bits), Wh), Ih))
  val fmOutType = HardType(Vec(Vec(SInt(width bits), Ww), Ih))

  val io = new Bundle{
    val weightIn = slave Stream(weightType)
    val fmIn = slave Stream(fmInType)
    val fmOut = master Stream(fmOutType)
    val peSwitch = out Bool()
  }
   val


}

object peArray {
  apply()
}
