FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY . .
ARG API_ORIGIN=http://app:8080
ENV API_ORIGIN=$API_ORIGIN
RUN npm run build
ENV NODE_ENV=production
USER node
EXPOSE 3011
CMD ["./node_modules/.bin/next","start","-p","3011"]
