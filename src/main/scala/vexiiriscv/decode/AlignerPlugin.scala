package vexiiriscv.decode

import spinal.core._
import spinal.lib.misc.pipeline.{Link, CtrlLink}
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.Global
import vexiiriscv.fetch.{Fetch, FetchPipelinePlugin}
import vexiiriscv.misc.PipelineService
import vexiiriscv.riscv.Riscv

import scala.collection.mutable.ArrayBuffer

class AlignerPlugin(fetchAt : Int = 3,
                    lanes : Int = 1) extends FiberPlugin with PipelineService{
  lazy val fpp = host[FetchPipelinePlugin]
  lazy val dpp = host[DecodePipelinePlugin]

  addLockable(fpp)
  addLockable(dpp)

  override def getConnectors(): Seq[Link] = logic.connectors

  val logic = during build new Area{
    val connectors = ArrayBuffer[Link]()
    Decode.LANES.set(lanes)
    Decode.INSTRUCTION_WIDTH.get


    assert(Decode.INSTRUCTION_WIDTH.get == Fetch.WORD_WIDTH.get)
    assert(lanes == 1)


    val up = fpp.ctrl(fetchAt).down
    val down = dpp.up
    val connector = CtrlLink(up, down)
    connectors += connector

    val feeder = new down.Area{
      this (Decode.ALIGNED_MASK, 0) := True
      this (Decode.INSTRUCTION, 0) := Fetch.WORD
      this (Global.PC, 0) := Fetch.WORD_PC
    }

  }
}