FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node package.json core.mjs engine.mjs index.mjs ./
USER node
ENV HOST=0.0.0.0 PORT=8787
EXPOSE 8787
CMD ["node", "index.mjs"]
