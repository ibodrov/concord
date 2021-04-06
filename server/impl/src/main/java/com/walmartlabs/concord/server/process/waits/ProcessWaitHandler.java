package com.walmartlabs.concord.server.process.waits;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.server.sdk.ProcessKey;
import com.walmartlabs.concord.server.sdk.ProcessStatus;
import org.immutables.value.Value;

import javax.annotation.Nullable;
import java.util.Set;

public interface ProcessWaitHandler<T extends AbstractWaitCondition> {

    WaitType getType();

    // TODO: old process_queue.wait_conditions code, remove me (1.84.0 or later)
    @Deprecated
    Set<ProcessStatus> getProcessStatuses();

    Result<T> process(ProcessKey processKey, ProcessStatus processStatus, T waits);

    @Value.Immutable
    interface Result<T extends AbstractWaitCondition> {

        /**
         * return null if the process doesn't have any wait conditions.
         */
        @Value.Parameter
        @Nullable
        T waitCondition();

        @Value.Parameter
        @Nullable
        String resumeEvent();

        static <T extends AbstractWaitCondition> Result<T> of(T waitCondition) {
            return ImmutableResult.of(waitCondition, null);
        }

        static <T extends AbstractWaitCondition> Result<T> of(String resumeEvent) {
            return ImmutableResult.of(null, resumeEvent);
        }
    }
}
