export const readableTimestamp = (timestampOrString: Date | string) => {
  const timestamp = typeof timestampOrString === "string" ? new Date(timestampOrString) : timestampOrString;
  // am/pm format
  const hours = timestamp.getHours() > 12 ? timestamp.getHours() - 12 : timestamp.getHours();
  const amPm = timestamp.getHours() > 12 ? 'PM' : 'AM';
  const minutes = timestamp.getMinutes() < 10 ? `0${timestamp.getMinutes()}` : timestamp.getMinutes();
    return `
  ${timestamp.getMonth() + 1}/${timestamp.getDate()}/${timestamp.getFullYear()} ${hours}:${minutes} ${amPm}
  `;
}