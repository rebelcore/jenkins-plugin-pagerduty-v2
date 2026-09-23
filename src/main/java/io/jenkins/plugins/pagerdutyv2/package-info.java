// Copyright 2010 Rebel Media
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * PagerDuty v2: sends PagerDuty Events API v2 trigger and resolve events from Jenkins builds.
 *
 * <p>Every event for one build carries the same {@code dedup_key}, {@link PayloadBuilder#dedupKey}
 * of {@code JOB_NAME#BUILD_NUMBER}. A trigger stores its payload on the build as a {@link
 * PagerDutyV2RunAction}; a resolve finds the most recent open action in the job's history and
 * replays that payload with {@code event_action} set to {@code resolve}, so a trigger and its
 * resolve can never drift apart. The routing key is never stored: it is read from the credential
 * at send time. The dedup key must stay a function of {@code JOB_NAME#BUILD_NUMBER} alone, or the
 * incidents still open in build history can no longer be resolved.
 *
 * <p>Two entry points share that logic. {@link PagerDutyV2Notifier} is the freestyle post-build
 * action and delegates to {@code PagerDutyV2Dispatcher}; {@link PagerDutyV2Step} is the {@code
 * pagerDutyV2} pipeline step. {@link PagerDutyV2RunListener} runs the dispatcher for a freestyle
 * build whose publisher could not run, typically because its agent disconnected, and {@link
 * PagerDutyV2HandledAction} makes sure a build is only handled once.
 */
package io.jenkins.plugins.pagerdutyv2;
