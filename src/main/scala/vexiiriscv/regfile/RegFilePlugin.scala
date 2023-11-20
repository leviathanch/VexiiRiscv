// SPDX-FileCopyrightText: 2023 "Everybody"
//
// SPDX-License-Identifier: MIT

package vexiiriscv.regfile

import spinal.core._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.eda.bench.{Bench, Rtl, XilinxStdTargets}
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.riscv.RegfileSpec

import scala.collection.mutable.ArrayBuffer

class RegFilePlugin(var spec : RegfileSpec,
                    var physicalDepth : Int,
                    var preferedWritePortForInit : String,
                    var asyncReadBySyncReadRevertedClk : Boolean = false,
                    var allOne : Boolean = false,
                    var syncRead : Boolean = true,
                    var latchBased : Boolean = false) extends FiberPlugin with RegfileService {
  withPrefix(spec.getName())

  override def writeLatency: Int = 1
  override def readLatency: Int = syncRead.toInt

  override def getPhysicalDepth = physicalDepth
  override def rfSpec = spec

  case class WriteSpec(port : RegFileWrite,
                       withReady : Boolean,
                       sharingKey : Any,
                       priority : Int) //Higher first

  def addressWidth = log2Up(physicalDepth)
  def dataWidth = spec.width
  val reads = ArrayBuffer[RegFileRead]()
  val writes = ArrayBuffer[WriteSpec]()
//  val bypasses = ArrayBuffer[RegFileBypass]()

  override def newRead(withReady : Boolean) = reads.addRet(RegFileRead(addressWidth, dataWidth, withReady))
  override def newWrite(withReady : Boolean, sharingKey : Any = new{}, priority : Int = 0) = writes.addRet(
    WriteSpec(
      port       = RegFileWrite(addressWidth, dataWidth, withReady),
      withReady  = withReady,
      sharingKey = sharingKey,
      priority   = priority
    )
  ).port
//  override def newBypass(priority : Int) : RegFileBypass = bypasses.addRet(RegFileBypass(addressWidth, dataWidth, priority))
  override def getWrites() = writes.map(_.port)

  override def retain() = lock.retain()
  override def release() = lock.release()

  val logic = during build new Area{
    val writeGroups = writes.groupByLinked(_.sharingKey)
    val writeMerges = for((key, elements) <- writeGroups) yield new Area{
      val bus = RegFileWrite(addressWidth, dataWidth, false)
      bus.valid   := elements.map(_.port.valid).orR

      val one = (elements.size == 1) generate new Area{
        val h = elements.head
        bus.address := h.port.address
        bus.data    := h.port.data
        if(h.withReady) h.port.ready := True
      }

      val multiple = (elements.size > 1) generate new Area {
        assert(elements.count(!_.withReady) <= 1)
        val sorted = elements.sortWith((a, b) => if(!a.withReady && b.withReady) true else a.priority > b.priority)
        assert(sorted.map(_.priority).indices.size == sorted.size, "Conflicting priorities for regfile writes")

        val mask = sorted.map(_.port.valid)
        val oh = OHMasking.firstV2(Vec(mask))
        bus.address := OhMux.or(oh, sorted.map(_.port.address))
        bus.data    := OhMux.or(oh, sorted.map(_.port.data))
        for((element, enable) <- (sorted, oh).zipped){
          if(element.withReady) element.port.ready := enable
        }
      }
    }

    val regfile = new Area{
      val fpga = !latchBased generate new RegFileMem(
        addressWidth = addressWidth,
        dataWidth = dataWidth,
        readsParameter = reads.map(e => RegFileReadParameter(withReady = e.withReady)),
        writesParameter = writeMerges.map(e => RegFileWriteParameter(withReady = false)).toList,
        headZero = spec.x0AlwaysZero,
        preferedWritePortForInit = writeGroups.zipWithIndex.find(_._1._2.exists(_.port.getName().contains(preferedWritePortForInit))).map(_._2).getOrElse(0),
        allOne = allOne,
        syncRead = syncRead,
        asyncReadBySyncReadRevertedClk = asyncReadBySyncReadRevertedClk
      )

      val latches = latchBased generate ???


      val io = latchBased match {
        case false => fpga.io
//        case true => latches.io
      }
    }


    (regfile.io.reads, reads).zipped.foreach(_ <> _)
    (regfile.io.writes, writeMerges.map(_.bus)).zipped.foreach(_ <> _)
//    (regfile.io.bypasses, bypasses).zipped.foreach(_ <> _)

    //Used for tracing in verilator sim
    val writeEvents = Vec(writeMerges.map(e => CombInit(e.bus)))
    writeEvents.setName(spec.getName()+"_write").addAttribute(Verilator.public)
    //    val doc = getService[DocPlugin]
    //    doc.property(writeEvents.getName() +"_count", writeEvents.size)
    //    doc.property(spec.getName() +"_PHYSICAL_DEPTH", physicalDepth)
  }
}