export const token = new URLSearchParams(window.location.search).get("jwt");
export const url = "/api/v1/";
//export const url = "http://localhost:8383/api/v1/";
//export const token =
  //"eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJndWVzdEBsYW1pc3BsdXMub3JnIiwiYXV0aCI6IlN1cGVyIEFkbWluIiwibmFtZSI6Ikd1ZXN0IEd1ZXN0IiwiZXhwIjoxNzExNDIxMzQzfQ.O1WThRKDL-lGgUIpG82ywnyaRrnz7Mr_F8JgVRi5_OZosCQD4-BBf672A2iYcLU9cCEvk-itVUH_dlYBdiIK0g";

export const errorMessage = (error, fallback) => {
  const response = error && error.response;
  const apierror = response && response.data && response.data.apierror;
  if (apierror && apierror.message) return apierror.message;
  if (response && response.data && typeof response.data.message === "string") return response.data.message;
  if (error && error.message === "Network Error")
    return "Cannot reach the server. Check that it is running and try again.";
  if (response && response.status === 403)
    return "You do not have permission to perform this action.";
  return fallback;
};
