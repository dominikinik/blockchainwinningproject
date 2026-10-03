# SLAna monorepo

This repository holds the SLAna services and Solana program. The frontend lives in [`frontend/`](frontend/README.md).

## Frontend

From the repository root:

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

Vite serves the app at `http://localhost:5173`. The devcontainer forwards that port, along with port `8899` for Surfpool. Run `npm --prefix frontend run build` to check TypeScript and create a production build.
