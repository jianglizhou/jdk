/*
 * Copyright (c) 2021, Google. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jvmti.h"

static jvmtiEnv *jvmti = NULL;

void JNICALL
OnClassPrepare(jvmtiEnv *jvmti_env,
            JNIEnv* jni_env,
            jthread thread,
            jclass klass) {
  jvmtiError err;
  char *sig, *generic;
  jint count;
  jmethodID *methods;

  err = (*jvmti)->GetClassSignature(jvmti, klass, &sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get class signature (%d)\n", err);
    return;
  }

  err = (*jvmti)->GetClassMethods(jvmti, klass, &count, &methods);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get class methods (%d)\n", err);
    return;
  }

  if (strcmp(sig, "C1") == 0 || strcmp(sig, "C2") == 0) {
    fprintf(stdout, "Class prepare event is receieved for %s, method count: %d\n", sig, count);
  }
}

static jint Agent_Initialize(JavaVM *jvm) {
  int rc;
  jvmtiEventCallbacks callbacks;

  printf(stdout, "In Agent_Initialize ...");

  if ((rc = (*jvm)->GetEnv(jvm, (void **)&jvmti, JVMTI_VERSION_1_1)) != JNI_OK) {
    fprintf(stderr, "Unable to create jvmtiEnv, GetEnv failed, error = %d\n", rc);
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(jvmtiEventCallbacks));

  callbacks.ClassPrepare = &OnClassPrepare;

  if ((rc = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks))) != JNI_OK) {
    fprintf(stderr, "SetEventCallbacks failed, error = %d\n", rc);
    return JNI_ERR;
  }

  return JNI_OK;
}


JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm);
}

JNIEXPORT void JNICALL
Java_JNIMethodBlockMemoryLeakTest_setNotificationMode(JNIEnv *env, jclass cls) {
  jvmtiError err;
  jthread t;

  if (jvmti == NULL) {
    printf("jvmtiEnv is not initialized");
    return;
  }

  err = (*jvmti)->GetCurrentThread(jvmti, &t);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: (%d)\n", err);
    return;
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE,
                                           JVMTI_EVENT_CLASS_PREPARE, t);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to enable JVMTI_EVENT_CLASS_PREPARE: (%d)\n", err);
  }
}
