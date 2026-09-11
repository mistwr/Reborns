FROM node:22-alpine
WORKDIR /app
COPY server/package.json server/core.mjs server/engine.mjs server/index.mjs ./
USER node
ENV HOST=0.0.0.0 PORT=8787 NODE_ENV=production
EXPOSE 8787
CMD ["node", "index.mjs"]
