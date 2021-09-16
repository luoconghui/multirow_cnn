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

  val peMtx = List.tabulate(Ih, Ww)((i,j) =>  peUnit(Wh, width))
}

object peArray {
  apply()
}
