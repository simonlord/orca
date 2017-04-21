/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.netflix.spinnaker.orca.ExecutionStatus.*
import com.netflix.spinnaker.orca.TaskResult
import com.netflix.spinnaker.orca.batch.exceptions.ExceptionHandler
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.orca.time.fixedClock
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.lang.RuntimeException
import java.time.Duration

@RunWith(JUnitPlatform::class)
class RunTaskHandlerSpec : Spek({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val task: DummyTask = mock()
  val exceptionHandler: ExceptionHandler<in Exception> = mock()
  val clock = fixedClock()

  val handler = RunTaskHandler(queue, repository, listOf(task), clock, listOf(exceptionHandler))

  fun resetMocks() = reset(queue, repository, task, exceptionHandler)

  describe("running a task") {

    describe("that completes successfully") {
      val pipeline = pipeline {
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)
      val taskResult = TaskResult(SUCCEEDED)

      beforeGroup {
        whenever(task.execute(any<Stage<*>>())).thenReturn(taskResult)
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("executes the task") {
        verify(task).execute(pipeline.stages.first())
      }

      it("completes the task") {
        verify(queue).push(check<CompleteTask> {
          it.status shouldEqual SUCCEEDED
        })
      }
    }

    describe("that is not yet complete") {
      val pipeline = pipeline {
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)
      val taskResult = TaskResult(RUNNING)
      val taskBackoffMs = 30_000L

      beforeGroup {
        whenever(task.execute(any())).thenReturn(taskResult)
        whenever(task.backoffPeriod).thenReturn(taskBackoffMs)
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("re-queues the command") {
        verify(queue).push(message, Duration.ofMillis(taskBackoffMs))
      }
    }

    describe("that fails") {
      val pipeline = pipeline {
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)
      val taskResult = TaskResult(TERMINAL)

      context("no overrides are in place") {
        beforeGroup {
          whenever(task.execute(any())).thenReturn(taskResult)
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("emits a failure event") {
          verify(queue).push(check<CompleteTask> {
            it.status shouldEqual TERMINAL
          })
        }
      }

      context("the task should not fail the whole pipeline, only the branch") {
        beforeGroup {
          pipeline.stages.first().context["failPipeline"] = false

          whenever(task.execute(any())).thenReturn(taskResult)
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
        }

        afterGroup(::resetMocks)
        afterGroup { pipeline.stages.first().context.clear() }

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("emits a failure event") {
          verify(queue).push(check<CompleteTask> {
            it.status shouldEqual STOPPED
          })
        }
      }

      context("the task should allow the pipeline to proceed") {
        beforeGroup {
          pipeline.stages.first().context["continuePipeline"] = true

          whenever(task.execute(any())).thenReturn(taskResult)
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
        }

        afterGroup(::resetMocks)
        afterGroup { pipeline.stages.first().context.clear() }

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("emits a failure event") {
          verify(queue).push(check<CompleteTask> {
            it.status shouldEqual FAILED_CONTINUE
          })
        }
      }
    }

    describe("that throws an exception") {
      val pipeline = pipeline {
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)

      context("that is not recoverable") {
        beforeGroup {
          whenever(task.execute(any())).thenThrow(RuntimeException("o noes"))
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
          whenever(exceptionHandler.handles(any())) doReturn true
          whenever(exceptionHandler.handle(anyOrNull(), any())) doReturn ExceptionHandler.Response(RuntimeException::class.qualifiedName, "o noes", ExceptionHandler.ResponseDetails("o noes"), false)
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("marks the task as terminal") {
          verify(queue).push(check<CompleteTask> {
            it.status shouldEqual TERMINAL
          })
        }
      }

      context("that is recoverable") {
        val taskBackoffMs = 30_000L

        beforeGroup {
          whenever(task.backoffPeriod) doReturn taskBackoffMs
          whenever(task.execute(any())) doThrow RuntimeException("o noes")
          whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
          whenever(exceptionHandler.handles(any())) doReturn true
          whenever(exceptionHandler.handle(anyOrNull(), any())) doReturn ExceptionHandler.Response(RuntimeException::class.qualifiedName, "o noes", ExceptionHandler.ResponseDetails("o noes"), true)
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("re-runs the task") {
          verify(queue).push(eq(message), eq(Duration.ofMillis(taskBackoffMs)))
        }
      }
    }

    describe("when the execution has stopped") {
      val pipeline = pipeline {
        status = TERMINAL
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("emits an event indicating that the task was canceled") {
        verify(queue).push(CompleteTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          message.taskId,
          CANCELED
        ))
      }

      it("does not execute the task") {
        verifyZeroInteractions(task)
      }
    }

    describe("when the execution has been canceled") {
      val pipeline = pipeline {
        status = RUNNING
        isCanceled = true
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("emits an event indicating that the task was canceled") {
        verify(queue).push(CompleteTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          message.taskId,
          CANCELED
        ))
      }

      it("does not execute the task") {
        verifyZeroInteractions(task)
      }
    }

    describe("when the task has exceeded its timeout") {
      val timeout = Duration.ofMinutes(5)
      val pipeline = pipeline {
        stage {
          type = "whatever"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            status = RUNNING
            startTime = clock.instant().minusMillis(timeout.toMillis() + 1).toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)

        whenever(task.timeout).thenReturn(timeout.toMillis())
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("fails the task") {
        verify(queue).push(CompleteTask(message, TERMINAL))
      }

      it("does not execute the task") {
        verify(task, never()).execute(any())
      }
    }

    describe("the context passed to the task") {
      val pipeline = pipeline {
        context["global"] = "foo"
        context["override"] = "global"
        stage {
          type = "whatever"
          context["stage"] = "foo"
          context["override"] = "stage"
          task {
            id = "1"
            implementingClass = DummyTask::class.qualifiedName
            startTime = clock.instant().toEpochMilli()
          }
        }
      }
      val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", DummyTask::class.java)
      val taskResult = TaskResult(SUCCEEDED)

      beforeGroup {
        whenever(task.execute(any<Stage<*>>())).thenReturn(taskResult)
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("merges stage and global contexts") {
        verify(task).execute(check {
          it.getContext() shouldEqual mapOf(
            "global" to "foo",
            "stage" to "foo",
            "override" to "stage"
          )
        })
      }
    }
  }

  describe("no such task") {
    val pipeline = pipeline {
      stage {
        type = "whatever"
      }
    }
    val message = RunTask(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id, "1", InvalidTask::class.java)

    beforeGroup {
      whenever(repository.retrievePipeline(message.executionId))
        .thenReturn(pipeline)
    }

    afterGroup(::resetMocks)

    action("the handler receives a message") {
      handler.handle(message)
    }

    it("does not run any tasks") {
      verifyZeroInteractions(task)
    }

    it("emits an error event") {
      verify(queue).push(isA<InvalidTaskType>())
    }
  }
})
