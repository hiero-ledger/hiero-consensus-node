FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY server.js workflow-graph.html ./
COPY .github/workflows/ ./.github/workflows/
EXPOSE 3001
CMD ["node", "server.js"]
