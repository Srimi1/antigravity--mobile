let count = 0;
document.querySelector('#counter').addEventListener('click', (event) => {
  event.currentTarget.textContent = `Count: ${++count}`;
});
fetch('./data.json').then((response) => {
  if (!response.ok) throw new Error(`Local data: ${response.status}`);
  return response.json();
}).then((data) => { document.querySelector('#data').textContent = data.message; })
  .catch((error) => { console.error(error); document.querySelector('#data').textContent = error.message; });
