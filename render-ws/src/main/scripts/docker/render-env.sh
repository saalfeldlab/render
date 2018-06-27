#!/bin/sh

# --------------------------------------------------------------
# This script exports environment variables (needed by jetty)
# that may have been "corrupted" by Ansible.

set -e

# --------------------------------------------------------------
# Strips quotes inserted by Ansible into environment variables
stripQuotes() {
  echo $* | sed -e 's/^"//' -e 's/"$//'
}

export JAVA_OPTIONS=$(stripQuotes ${JAVA_OPTIONS})