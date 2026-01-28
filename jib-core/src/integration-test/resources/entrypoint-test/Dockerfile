FROM busybox:latest

# Copy the entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Set the entrypoint to wrap any CMD
ENTRYPOINT ["/entrypoint.sh"]
