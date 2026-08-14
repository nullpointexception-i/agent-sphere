FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

# fholzer/nginx-brotli：官方 nginx + ngx_brotli（brotli 压缩），目录结构兼容
FROM fholzer/nginx-brotli:latest
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=build /app/public/index.html /usr/share/nginx/html/index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
