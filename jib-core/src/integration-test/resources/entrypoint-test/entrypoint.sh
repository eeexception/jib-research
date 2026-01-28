#!/bin/sh
# Test entrypoint script that wraps the actual command
echo "Entrypoint: Preparing environment..."
exec "$@"
